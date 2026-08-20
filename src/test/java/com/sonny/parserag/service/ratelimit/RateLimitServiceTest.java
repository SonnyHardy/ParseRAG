package com.sonny.parserag.service.ratelimit;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.observability.TestMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Garde de débit par consommateur et par plan (issues #14, #54).
 * <p>
 * Deux propriétés sont figées ici parce qu'elles se sont révélées coûteuses à retrouver :
 * l'isolation entre appelants — la raison d'avoir écarté une garde globale — et la reconstruction
 * du bucket quand le plan change, sans quoi un client qui vient d'upgrader resterait cadencé à
 * l'ancien débit jusqu'au prochain redémarrage.
 */
class RateLimitServiceTest {

    private static RateLimitService service() {
        AppProperties props = new AppProperties();
        AppProperties.RateLimit rl = props.getRateLimit();
        rl.setFreeRequestsPerMinute(10);
        rl.setStarterRequestsPerMinute(30);
        rl.setProRequestsPerMinute(100);
        rl.setScaleRequestsPerMinute(300);
        return new RateLimitService(props, TestMetrics.metrics());
    }

    @Test
    void freePlanAllowsTenThenBlocksEleventh() {
        RateLimitService service = service();

        for (int i = 1; i <= 10; i++) {
            assertTrue(service.tryConsume("rapidapi:alice", Plan.FREE).isConsumed(),
                    "requête " + i + " doit passer");
        }
        assertFalse(service.tryConsume("rapidapi:alice", Plan.FREE).isConsumed(),
                "la 11ᵉ doit être bloquée (429)");
    }

    @Test
    void oneConsumerBurningItsBudgetDoesNotAffectTheOthers() {
        // Le scénario qui a condamné la garde globale : 300 clients dans les clous de leur plan ne
        // doivent pas payer pour un seul qui s'emballe.
        RateLimitService service = service();

        for (int i = 1; i <= 10; i++) {
            assertTrue(service.tryConsume("rapidapi:alice", Plan.FREE).isConsumed());
        }
        assertFalse(service.tryConsume("rapidapi:alice", Plan.FREE).isConsumed(), "alice a épuisé son budget");

        assertTrue(service.tryConsume("rapidapi:bob", Plan.FREE).isConsumed(), "bob n'a rien à voir avec alice");
        assertTrue(service.tryConsume("key:1c3f", Plan.FREE).isConsumed(), "ni la clé interne");
    }

    @Test
    void limitDiffersByPlan() {
        RateLimitService service = service();
        assertEquals(10, service.limitForPlan(Plan.FREE));
        assertEquals(30, service.limitForPlan(Plan.STARTER));
        assertEquals(100, service.limitForPlan(Plan.PRO));
        assertEquals(300, service.limitForPlan(Plan.SCALE));
    }

    @ParameterizedTest
    @EnumSource(Plan.class)
    void everyPlanHasAConfiguredRate(Plan plan) {
        // Le switch est exhaustif : aucun plan ne doit lever ni retourner 0, ce qui bloquerait
        // toutes les requêtes de son palier.
        assertTrue(service().limitForPlan(plan) > 0);
    }

    @Test
    void upgradingThePlanRebuildsTheBucketWithTheNewCapacity() {
        RateLimitService service = service();

        for (int i = 1; i <= 10; i++) {
            service.tryConsume("rapidapi:alice", Plan.FREE);
        }
        assertFalse(service.tryConsume("rapidapi:alice", Plan.FREE).isConsumed());

        // Même consommateur, plan supérieur : le bucket est reconstruit à la capacité du nouveau
        // palier plutôt que de rester bloqué sur l'ancien jusqu'au redémarrage.
        assertTrue(service.tryConsume("rapidapi:alice", Plan.PRO).isConsumed());
        assertEquals(98, service.tryConsume("rapidapi:alice", Plan.PRO).getRemainingTokens());
    }

    @Test
    void remainingTokensDecreaseFromThePlanLimit() {
        RateLimitService service = service();

        assertEquals(9, service.tryConsume("rapidapi:alice", Plan.FREE).getRemainingTokens());
        assertEquals(8, service.tryConsume("rapidapi:alice", Plan.FREE).getRemainingTokens());
    }

    @Test
    void refillIsNotImmediate() {
        // Un bucket qui se réalimenterait instantanément ne protégerait rien.
        RateLimitService service = service();
        for (int i = 1; i <= 10; i++) {
            service.tryConsume("rapidapi:alice", Plan.FREE);
        }

        assertTrue(service.tryConsume("rapidapi:alice", Plan.FREE).getNanosToWaitForRefill() > 0,
                "le client doit pouvoir déduire un Retry-After exploitable");
    }
}
