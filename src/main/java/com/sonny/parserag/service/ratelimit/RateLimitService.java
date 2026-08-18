package com.sonny.parserag.service.ratelimit;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.observability.ParseRagMetrics;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting par API key via token bucket Bucket4j (issue #14).
 * <p>
 * Un {@link Bucket} en mémoire par clé, indexé par {@code apiKey.id} dans une
 * {@link ConcurrentHashMap}. La capacité du bucket = débit/minute du plan
 * ({@link AppProperties.RateLimit#forPlan}), avec un refill « greedy » qui réalimente
 * les jetons continûment sur une fenêtre d'une minute.
 * <p>
 * <strong>MVP mono-instance :</strong> le stockage est in-process. Pour un déploiement
 * multi-instances, migrer vers {@code bucket4j-redis} (backend distribué natif de Bucket4j).
 * <p>
 * <strong>Changement de plan à chaud :</strong> le bucket mémorise le plan avec lequel il a été
 * construit ; si le plan de la clé change (upgrade/downgrade), il est reconstruit à la volée avec
 * la nouvelle capacité — pas besoin de redémarrer. La reconstruction se fait de façon atomique via
 * {@link ConcurrentHashMap#compute} et ré-amorce les jetons à plein (événement rare, favorable au
 * client sur un upgrade).
 */
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private static final Duration REFILL_WINDOW = Duration.ofMinutes(1);

    private final AppProperties appProperties;
    private final ParseRagMetrics metrics;
    private final ConcurrentHashMap<UUID, PlannedBucket> buckets = new ConcurrentHashMap<>();

    /**
     * La map n'a aucune éviction : elle grandit avec le nombre de clés vues depuis le démarrage.
     * Cette gauge est la mesure directe de l'empreinte mémoire que l'issue #35 veut borner.
     */
    @PostConstruct
    void registerMetrics() {
        metrics.registerBucketsGauge(buckets::size);
    }

    /** Bucket + plan avec lequel il a été construit, pour détecter un changement de plan à chaud. */
    private record PlannedBucket(Plan plan, Bucket bucket) {}

    /**
     * Tente de consommer un jeton pour la clé donnée. Reconstruit le bucket si le plan a changé.
     *
     * @return la sonde Bucket4j : {@code isConsumed()}, {@code getRemainingTokens()},
     *         {@code getNanosToWaitForRefill()} pour construire les headers de réponse.
     */
    public ConsumptionProbe tryConsume(ApiKey apiKey) {
        PlannedBucket pb = buckets.compute(apiKey.getId(), (id, existing) ->
                (existing == null || existing.plan() != apiKey.getPlan())
                        ? new PlannedBucket(apiKey.getPlan(), newBucket(apiKey.getPlan()))
                        : existing);
        return pb.bucket().tryConsumeAndReturnRemaining(1);
    }

    /** Capacité (jetons) du bucket pour le plan — sert aussi de {@code X-RateLimit-Limit}. */
    public int limitForPlan(Plan plan) {
        return appProperties.getRateLimit().forPlan(plan);
    }

    private Bucket newBucket(Plan plan) {
        int capacity = appProperties.getRateLimit().forPlan(plan);
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, REFILL_WINDOW)
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
