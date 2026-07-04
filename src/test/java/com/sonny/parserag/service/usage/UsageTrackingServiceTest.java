package com.sonny.parserag.service.usage;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.model.response.UsageResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests de la logique pure de {@link UsageTrackingService} : cycle « anniversaire » (ancré sur le
 * jour de création de la clé, bornage fin de mois) et assemblage de la réponse /usage.
 * <p>
 * Aucune DB ni contexte Spring : les fonctions de cycle sont statiques et {@code today} est fourni
 * explicitement. Les méthodes déléguant au repository (currentCount, recordSuccessfulParse) relèvent
 * d'un test d'intégration avec Postgres — hors périmètre ici.
 */
class UsageTrackingServiceTest {

    /** Clé avec un jour de création donné (le reste de la date est indifférent au cycle). */
    private static ApiKey keyCreatedOnDay(int day) {
        ApiKey k = new ApiKey();
        k.setCreatedAt(LocalDateTime.of(2024, 1, day, 9, 30)); // janvier a 31 jours → tout jour valide
        k.setPlan(Plan.FREE);
        return k;
    }

    // ── Cycle « standard » : ancre en milieu de mois (jour 2) ────────────────

    @Test
    void cycleMidMonth_todayAfterAnchor() {
        ApiKey key = keyCreatedOnDay(2);
        LocalDate today = LocalDate.of(2025, 6, 15); // 15 ≥ 2 → cycle démarré ce mois-ci
        assertEquals("2025-06", UsageTrackingService.periodKey(key, today));
        assertEquals(LocalDate.of(2025, 6, 2), UsageTrackingService.cycleStart(key, today));
        assertEquals(LocalDate.of(2025, 7, 2), UsageTrackingService.resetDate(key, today));
    }

    @Test
    void cycleMidMonth_todayBeforeAnchor() {
        ApiKey key = keyCreatedOnDay(2);
        LocalDate today = LocalDate.of(2025, 6, 1); // 1 < 2 → cycle démarré le mois dernier
        assertEquals("2025-05", UsageTrackingService.periodKey(key, today));
        assertEquals(LocalDate.of(2025, 5, 2), UsageTrackingService.cycleStart(key, today));
        assertEquals(LocalDate.of(2025, 6, 2), UsageTrackingService.resetDate(key, today));
    }

    @Test
    void cycleMidMonth_todayExactlyOnAnchor() {
        ApiKey key = keyCreatedOnDay(2);
        LocalDate today = LocalDate.of(2025, 6, 2); // pile le jour d'ancrage → nouveau cycle
        assertEquals("2025-06", UsageTrackingService.periodKey(key, today));
        assertEquals(LocalDate.of(2025, 7, 2), UsageTrackingService.resetDate(key, today));
    }

    // ── Ancre au 1er du mois ─────────────────────────────────────────────────

    @Test
    void cycleAnchoredOnFirst() {
        ApiKey key = keyCreatedOnDay(1);
        LocalDate today = LocalDate.of(2025, 6, 10);
        assertEquals("2025-06", UsageTrackingService.periodKey(key, today));
        assertEquals(LocalDate.of(2025, 6, 1), UsageTrackingService.cycleStart(key, today));
        assertEquals(LocalDate.of(2025, 7, 1), UsageTrackingService.resetDate(key, today));
    }

    // ── Bornage fin de mois : ancre au 31 ────────────────────────────────────

    @Test
    void anchor31_inShortMonth_clampsToLastDay() {
        ApiKey key = keyCreatedOnDay(31);
        LocalDate today = LocalDate.of(2025, 4, 15); // avril = 30 jours ; ancre bornée à 30
        // 15 < 30 → cycle démarré en mars (31), reset borné à avril 30
        assertEquals("2025-03", UsageTrackingService.periodKey(key, today));
        assertEquals(LocalDate.of(2025, 3, 31), UsageTrackingService.cycleStart(key, today));
        assertEquals(LocalDate.of(2025, 4, 30), UsageTrackingService.resetDate(key, today));
    }

    @Test
    void anchor31_reAnchorsToFullDayNextMonth() {
        ApiKey key = keyCreatedOnDay(31);
        LocalDate today = LocalDate.of(2025, 4, 30); // pile la fin de mois bornée → nouveau cycle
        assertEquals("2025-04", UsageTrackingService.periodKey(key, today));
        // le reset revient au 31 quand le mois suivant (mai) le permet
        assertEquals(LocalDate.of(2025, 5, 31), UsageTrackingService.resetDate(key, today));
    }

    @Test
    void anchor31_februaryNonLeap() {
        ApiKey key = keyCreatedOnDay(31);
        LocalDate today = LocalDate.of(2025, 2, 28); // février 2025 = 28 jours
        assertEquals("2025-02", UsageTrackingService.periodKey(key, today));
        assertEquals(LocalDate.of(2025, 2, 28), UsageTrackingService.cycleStart(key, today));
        assertEquals(LocalDate.of(2025, 3, 31), UsageTrackingService.resetDate(key, today));
    }

    @Test
    void anchor31_februaryLeap() {
        ApiKey key = keyCreatedOnDay(31);
        LocalDate today = LocalDate.of(2024, 2, 29); // 2024 bissextile → février = 29 jours
        assertEquals("2024-02", UsageTrackingService.periodKey(key, today));
        assertEquals(LocalDate.of(2024, 2, 29), UsageTrackingService.cycleStart(key, today));
        assertEquals(LocalDate.of(2024, 3, 31), UsageTrackingService.resetDate(key, today));
    }

    // ── Passage d'année ──────────────────────────────────────────────────────

    @Test
    void yearRollover_todayBeforeAnchorInJanuary() {
        ApiKey key = keyCreatedOnDay(10);
        LocalDate today = LocalDate.of(2025, 1, 5); // 5 < 10 → cycle démarré en décembre 2024
        assertEquals("2024-12", UsageTrackingService.periodKey(key, today));
        assertEquals(LocalDate.of(2024, 12, 10), UsageTrackingService.cycleStart(key, today));
        assertEquals(LocalDate.of(2025, 1, 10), UsageTrackingService.resetDate(key, today));
    }

    @Test
    void yearRollover_resetCrossesIntoNextYear() {
        ApiKey key = keyCreatedOnDay(10);
        LocalDate today = LocalDate.of(2025, 12, 20); // 20 ≥ 10 → cycle démarré en décembre
        assertEquals("2025-12", UsageTrackingService.periodKey(key, today));
        assertEquals(LocalDate.of(2026, 1, 10), UsageTrackingService.resetDate(key, today));
    }

    // ── Défensif : createdAt null → ancre au 1er ─────────────────────────────

    @Test
    void nullCreatedAt_fallsBackToFirstOfMonth() {
        ApiKey key = new ApiKey();
        key.setPlan(Plan.FREE); // createdAt null
        LocalDate today = LocalDate.of(2025, 6, 15);
        assertEquals("2025-06", UsageTrackingService.periodKey(key, today));
        assertEquals(LocalDate.of(2025, 7, 1), UsageTrackingService.resetDate(key, today));
    }

    // ── buildUsage : assemblage de la réponse /usage (logique pure) ──────────

    private static UsageTrackingService serviceWithQuotas() {
        AppProperties props = new AppProperties();
        props.getQuota().setFreeMonthlyDocs(100);
        props.getQuota().setStarterMonthlyDocs(1000);
        props.getQuota().setProMonthlyDocs(5000);
        props.getQuota().setScaleMonthlyDocs(20000);
        return new UsageTrackingService(null, props); // repo inutile pour buildUsage
    }

    @Test
    void buildUsage_computesRemainingAndLowercasesPlan() {
        UsageTrackingService svc = serviceWithQuotas();
        ApiKey key = keyCreatedOnDay(2);
        LocalDate today = LocalDate.of(2025, 6, 15);

        UsageResponse r = svc.buildUsage(key, 47, today);

        assertEquals("free", r.plan());
        assertEquals(47, r.docsUsed());
        assertEquals(100, r.docsLimit());
        assertEquals(53, r.docsRemaining());
        assertEquals("2025-06", r.period());
        assertEquals("2025-07-02", r.resetDate());
    }

    @Test
    void buildUsage_remainingNeverNegative() {
        UsageTrackingService svc = serviceWithQuotas();
        ApiKey key = keyCreatedOnDay(2);

        // quota atteint (voire dépassé par une race) → remaining borné à 0
        UsageResponse r = svc.buildUsage(key, 105, LocalDate.of(2025, 6, 15));

        assertEquals(105, r.docsUsed());
        assertEquals(100, r.docsLimit());
        assertEquals(0, r.docsRemaining());
    }

    @ParameterizedTest
    @CsvSource({
            "FREE,100",
            "STARTER,1000",
            "PRO,5000",
            "SCALE,20000"
    })
    void buildUsage_limitFollowsPlan(Plan plan, int expectedLimit) {
        UsageTrackingService svc = serviceWithQuotas();
        ApiKey key = keyCreatedOnDay(2);
        key.setPlan(plan);

        UsageResponse r = svc.buildUsage(key, 0, LocalDate.of(2025, 6, 15));

        assertEquals(plan.name().toLowerCase(), r.plan());
        assertEquals(expectedLimit, r.docsLimit());
        assertEquals(expectedLimit, r.docsRemaining());
    }
}
