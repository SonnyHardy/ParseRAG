package com.sonny.parserag.service.ratelimit;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.observability.ParseRagMetrics;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Garde de débit <strong>par consommateur et par plan</strong> (issues #14, #54).
 * <p>
 * Un {@link Bucket} en mémoire par appelant, indexé par son identité — celle annoncée par RapidAPI
 * ({@code X-RapidAPI-User}) ou, sur le chemin interne, l'identifiant de la clé. Sa capacité est le
 * débit du plan ({@link AppProperties.RateLimit#forPlan}), réglable dans {@code application.yaml}.
 *
 * <p><strong>Par consommateur, jamais globalement.</strong> Une garde globale est collective : à
 * 600 req/min partagées, 300 clients faisant chacun 10 req/min — tous dans les clous de leur plan —
 * se prendraient un 429. Le fautif doit être le seul gêné.
 *
 * <p><strong>Articulation avec RapidAPI.</strong> La place de marché cadence déjà les consommateurs
 * selon leur abonnement ; ces paliers-ci sont une seconde barrière, à tenir <em>au moins aussi
 * hauts</em> que ceux du listing. Réglés plus bas, ce sont eux qui mordraient en premier et le
 * client se verrait refuser un débit qu'il a pourtant payé.
 *
 * <p><strong>Changement de plan à chaud :</strong> le bucket mémorise le plan avec lequel il a été
 * construit ; si le plan change (upgrade/downgrade côté RapidAPI), il est reconstruit à la volée
 * avec la nouvelle capacité — pas besoin de redémarrer. La reconstruction passe par
 * {@link ConcurrentHashMap#compute} et ré-amorce les jetons à plein : événement rare, et favorable
 * au client sur un upgrade.
 *
 * <p><strong>Ce que cette garde ne couvre pas.</strong> Un attaquant en possession du secret proxy
 * peut faire varier {@code X-RapidAPI-User} et s'offrir un bucket neuf à chaque requête. Le rempart
 * contre ce scénario est le secret lui-même ({@code RapidApiProxyFilter}), pas ce compteur. Et la
 * ressource réellement rare sur {@code /parse} est la <em>concurrence</em>, pas le débit.
 *
 * <p><strong>MVP mono-instance :</strong> stockage in-process, et la map n'a aucune éviction — elle
 * grandit avec le nombre de consommateurs vus depuis le démarrage, d'où la gauge (issue #35). Pour
 * plusieurs réplicas, passer à {@code bucket4j-redis}.
 */
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private static final Duration REFILL_WINDOW = Duration.ofMinutes(1);

    private final AppProperties appProperties;
    private final ParseRagMetrics metrics;
    private final ConcurrentHashMap<String, PlannedBucket> buckets = new ConcurrentHashMap<>();

    @PostConstruct
    void registerMetrics() {
        metrics.registerBucketsGauge(buckets::size);
    }

    /** Bucket + plan avec lequel il a été construit, pour détecter un changement de plan à chaud. */
    private record PlannedBucket(Plan plan, Bucket bucket) {}

    /**
     * Tente de consommer un jeton pour le consommateur donné, au débit de son plan. Reconstruit le
     * bucket si le plan a changé depuis la dernière requête.
     *
     * @param consumer identité de l'appelant, telle que résolue par la chaîne de filtres
     * @param plan     plan applicable à cette requête
     * @return la sonde Bucket4j : {@code isConsumed()}, {@code getRemainingTokens()} et
     *         {@code getNanosToWaitForRefill()} pour construire le {@code Retry-After}
     */
    public ConsumptionProbe tryConsume(String consumer, Plan plan) {
        PlannedBucket pb = buckets.compute(consumer, (c, existing) ->
                (existing == null || existing.plan() != plan)
                        ? new PlannedBucket(plan, newBucket(plan))
                        : existing);
        return pb.bucket().tryConsumeAndReturnRemaining(1);
    }

    /** Débit autorisé (requêtes/minute) pour le plan — sert aussi de message au client sur un 429. */
    public int limitForPlan(Plan plan) {
        return appProperties.getRateLimit().forPlan(plan);
    }

    private Bucket newBucket(Plan plan) {
        int capacity = limitForPlan(plan);
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, REFILL_WINDOW)
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }
}
