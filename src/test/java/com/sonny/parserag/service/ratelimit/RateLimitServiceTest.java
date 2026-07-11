package com.sonny.parserag.service.ratelimit;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.entity.Plan;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vérifie le token bucket par API key de {@link RateLimitService} (issue #14). */
class RateLimitServiceTest {

    private static RateLimitService service() {
        AppProperties props = new AppProperties();
        AppProperties.RateLimit rl = props.getRateLimit();
        rl.setFreeRequestsPerMinute(10);
        rl.setStarterRequestsPerMinute(30);
        rl.setProRequestsPerMinute(100);
        rl.setScaleRequestsPerMinute(300);
        return new RateLimitService(props);
    }

    private static ApiKey key(Plan plan) {
        ApiKey k = new ApiKey();
        k.setId(UUID.randomUUID());
        k.setPlan(plan);
        return k;
    }

    /** Critère de validation de l'issue : plan Free → 10 requêtes OK, la 11ème est refusée. */
    @Test
    void freePlanAllowsTenThenBlocksEleventh() {
        RateLimitService service = service();
        ApiKey apiKey = key(Plan.FREE);

        for (int i = 1; i <= 10; i++) {
            assertTrue(service.tryConsume(apiKey).isConsumed(), "requête " + i + " doit passer");
        }
        assertFalse(service.tryConsume(apiKey).isConsumed(), "la 11ème doit être bloquée (429)");
    }

    @Test
    void remainingTokensDecreaseFromLimit() {
        RateLimitService service = service();
        ApiKey apiKey = key(Plan.FREE);

        assertEquals(9, service.tryConsume(apiKey).getRemainingTokens());
        assertEquals(8, service.tryConsume(apiKey).getRemainingTokens());
    }

    @Test
    void limitDiffersByPlan() {
        RateLimitService service = service();
        assertEquals(10, service.limitForPlan(Plan.FREE));
        assertEquals(30, service.limitForPlan(Plan.STARTER));
        assertEquals(100, service.limitForPlan(Plan.PRO));
        assertEquals(300, service.limitForPlan(Plan.SCALE));
    }

    /** Starter (30/min) ne doit pas être bloqué après 11 requêtes, contrairement à Free. */
    @Test
    void higherPlanToleratesMoreRequests() {
        RateLimitService service = service();
        ApiKey apiKey = key(Plan.STARTER);

        for (int i = 1; i <= 11; i++) {
            assertTrue(service.tryConsume(apiKey).isConsumed(), "Starter requête " + i + " doit passer");
        }
    }

    /** Upgrade FREE→STARTER à chaud : la nouvelle limite (30) s'applique sans redémarrage. */
    @Test
    void planUpgradeRebuildsBucketWithNewLimit() {
        RateLimitService service = service();
        ApiKey apiKey = key(Plan.FREE);

        // Épuise le quota FREE (10) : la 11ème serait bloquée.
        for (int i = 0; i < 10; i++) {
            service.tryConsume(apiKey);
        }
        assertFalse(service.tryConsume(apiKey).isConsumed(), "FREE épuisé");

        // Upgrade → le bucket est reconstruit à la capacité STARTER.
        apiKey.setPlan(Plan.STARTER);
        for (int i = 1; i <= 30; i++) {
            assertTrue(service.tryConsume(apiKey).isConsumed(), "STARTER requête " + i + " doit passer");
        }
        assertFalse(service.tryConsume(apiKey).isConsumed(), "STARTER épuisé à la 31ème");
    }

    /** Downgrade STARTER→FREE à chaud : la limite retombe à 10. */
    @Test
    void planDowngradeRebuildsBucketWithLowerLimit() {
        RateLimitService service = service();
        ApiKey apiKey = key(Plan.STARTER);

        service.tryConsume(apiKey);
        apiKey.setPlan(Plan.FREE);

        for (int i = 1; i <= 10; i++) {
            assertTrue(service.tryConsume(apiKey).isConsumed(), "FREE requête " + i + " doit passer");
        }
        assertFalse(service.tryConsume(apiKey).isConsumed(), "FREE épuisé à la 11ème");
    }

    /** Chaque clé a son propre bucket : consommer l'une n'affecte pas l'autre. */
    @Test
    void bucketsAreIsolatedPerKey() {
        RateLimitService service = service();
        ApiKey a = key(Plan.FREE);
        ApiKey b = key(Plan.FREE);

        for (int i = 0; i < 10; i++) {
            service.tryConsume(a);
        }
        assertFalse(service.tryConsume(a).isConsumed(), "clé A épuisée");
        assertTrue(service.tryConsume(b).isConsumed(), "clé B intacte");
    }
}
