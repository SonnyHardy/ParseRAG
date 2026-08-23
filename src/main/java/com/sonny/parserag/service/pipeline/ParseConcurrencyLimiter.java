package com.sonny.parserag.service.pipeline;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.exception.ParseRagException;
import com.sonny.parserag.observability.ParseRagMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Borne le nombre de parses simultanés (issue #56).
 *
 * <p><strong>Pourquoi une borne de concurrence et non un débit.</strong> Le rate limiting compte
 * des requêtes par minute ; ce qui sature l'origine, ce sont des requêtes <em>en cours</em>. Un
 * parse charge 50 Mo en mémoire, rouvre le PDF six fois et rend des pages à 150 DPI (~9 Mo par
 * image) : la mémoire est la ressource rare, et elle se mesure en simultanéité, pas en cadence.
 * Avec les 200 threads Tomcat par défaut, rien n'empêchait 200 parses concurrents de vider le tas.
 *
 * <p><strong>Refuser franchement plutôt qu'empiler.</strong> Passé une courte attente, la requête
 * est refusée par un {@code 503} portant {@code Retry-After}, valorisé à l'attente déjà consentie —
 * la moins mauvaise indication disponible, personne ne sachant quand une place se libérera. Une
 * file d'attente sans borne ne
 * ferait que déplacer le problème dans les threads du serveur : le client attendrait de toute
 * façon, mais sans savoir qu'il attend, et le proxy finirait par couper à sa place.
 *
 * <p>Le sémaphore est <em>équitable</em> : sous saturation continue, les requêtes sont servies dans
 * l'ordre d'arrivée. Sans cela, une requête malchanceuse peut attendre indéfiniment pendant que
 * d'autres, arrivées après, passent devant.
 */
@Slf4j
@Component
public class ParseConcurrencyLimiter {

    private final Semaphore slots;
    private final int maxWaitSeconds;
    private final ParseRagMetrics metrics;

    public ParseConcurrencyLimiter(AppProperties appProperties, ParseRagMetrics metrics) {
        AppProperties.Parse config = appProperties.getParse();
        this.slots = new Semaphore(config.getMaxConcurrent(), true);
        this.maxWaitSeconds = config.getMaxWaitSeconds();
        this.metrics = metrics;
        metrics.registerParseSlotsGauge(slots::availablePermits);
        log.info("Borne de concurrence du parsing : {} places, attente max {} s",
                config.getMaxConcurrent(), config.getMaxWaitSeconds());
    }

    /**
     * Exécute le travail avec une place réservée, ou refuse.
     *
     * @throws ParseRagException {@code 503 SERVICE_BUSY} si aucune place ne se libère à temps
     */
    public <T> T withSlot(Supplier<T> work) {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(maxWaitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // Ne jamais avaler l'interruption : le thread doit rester interruptible pour que
            // l'arrêt gracieux puisse faire son travail.
            Thread.currentThread().interrupt();
            throw new ParseRagException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_BUSY",
                    "Server is shutting down. Please retry.", maxWaitSeconds);
        }

        if (!acquired) {
            metrics.parseRejectedBusy();
            log.warn("Parse refusé : {} places toutes occupées après {} s d'attente",
                    slots.availablePermits(), maxWaitSeconds);
            /*
             * Retry-After = l'attente deja consentie. C'est l'echelle de la contention observee,
             * donc la moins mauvaise indication disponible : personne ne sait quand une place se
             * liberera. Borne a 1 s : un Retry-After a 0 (attente configuree a zero) inviterait a
             * revenir immediatement, donc a se faire refuser a nouveau.
             * Le javadoc de cette classe annoncait cet en-tete alors qu'il n'etait pas pose - un
             * 503 sans Retry-After laisse le client marteler ou abandonner.
             */
            int retryAfter = Math.max(1, maxWaitSeconds);
            throw new ParseRagException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_BUSY",
                    "Server is at capacity. Please retry in %d seconds.".formatted(retryAfter),
                    retryAfter);
        }

        try {
            return work.get();
        } finally {
            slots.release();
        }
    }
}
