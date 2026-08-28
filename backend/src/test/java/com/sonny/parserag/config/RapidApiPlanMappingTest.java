package com.sonny.parserag.config;

import com.sonny.parserag.entity.Plan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Traduction {@code X-RapidAPI-Subscription} → {@link Plan} (issue #54).
 * <p>
 * La règle qui compte est celle de l'échec : tout ce qui n'est pas reconnu retombe sur le plan le
 * plus restrictif. Une entrée inconnue vient soit d'un plan renommé dans le tableau de bord, soit
 * d'un appelant qui forge l'en-tête — dans les deux cas, accorder le bénéfice du doute reviendrait
 * à offrir un palier payant.
 */
class RapidApiPlanMappingTest {

    private static AppProperties.RapidApi mapping() {
        AppProperties.RapidApi r = new AppProperties.RapidApi();
        r.setPlanMapping(Map.of(
                "BASIC", Plan.FREE, "PRO", Plan.STARTER, "ULTRA", Plan.PRO, "MEGA", Plan.SCALE));
        return r;
    }

    @Test
    void mapsEachRapidApiPlanToItsInternalPlan() {
        AppProperties.RapidApi r = mapping();
        assertEquals(Plan.FREE, r.planFor("BASIC"));
        assertEquals(Plan.STARTER, r.planFor("PRO"));
        assertEquals(Plan.PRO, r.planFor("ULTRA"));
        assertEquals(Plan.SCALE, r.planFor("MEGA"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "ENTERPRISE", "MEGA-PLUS", "'; DROP TABLE"})
    void unknownSubscriptionFallsBackToFree(String subscription) {
        assertEquals(Plan.FREE, mapping().planFor(subscription));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void absentSubscriptionFallsBackToFree(String subscription) {
        assertEquals(Plan.FREE, mapping().planFor(subscription));
    }

    @Test
    void matchingIgnoresCaseAndSurroundingSpace() {
        assertEquals(Plan.SCALE, mapping().planFor("mega"));
        assertEquals(Plan.SCALE, mapping().planFor("  MEGA  "));
    }

    @Test
    void anEmptyMappingGrantsNothing() {
        // Défaut de configuration : mieux vaut tout le monde en FREE qu'un palier ouvert au hasard.
        assertEquals(Plan.FREE, new AppProperties.RapidApi().planFor("MEGA"));
    }
}
