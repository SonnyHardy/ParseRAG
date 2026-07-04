package com.sonny.parserag.config;

import com.sonny.parserag.entity.Plan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vérifie le mapping plan → quota mensuel de {@link AppProperties.Quota#forPlan}. */
class QuotaForPlanTest {

    private static AppProperties.Quota quota() {
        AppProperties.Quota q = new AppProperties.Quota();
        q.setFreeMonthlyDocs(100);
        q.setStarterMonthlyDocs(1000);
        q.setProMonthlyDocs(5000);
        q.setScaleMonthlyDocs(20000);
        return q;
    }

    @Test
    void mapsEachPlanToItsQuota() {
        AppProperties.Quota q = quota();
        assertEquals(100, q.forPlan(Plan.FREE));
        assertEquals(1000, q.forPlan(Plan.STARTER));
        assertEquals(5000, q.forPlan(Plan.PRO));
        assertEquals(20000, q.forPlan(Plan.SCALE));
    }

    @ParameterizedTest
    @EnumSource(Plan.class)
    void everyPlanIsCovered(Plan plan) {
        // le switch est exhaustif : aucun plan ne doit lever ni retourner 0
        assertTrue(quota().forPlan(plan) > 0);
    }
}
