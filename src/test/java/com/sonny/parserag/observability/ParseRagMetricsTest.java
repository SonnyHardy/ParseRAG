package com.sonny.parserag.observability;

import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.observability.ParseRagMetrics.AuthFailure;
import com.sonny.parserag.observability.ParseRagMetrics.Outcome;
import com.sonny.parserag.observability.ParseRagMetrics.Stage;
import com.sonny.parserag.observability.ParseRagMetrics.TokenType;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vérifie les noms et les tags émis par la façade d'instrumentation (issue #38) — c'est le contrat
 * sur lequel s'appuieront les dashboards, et le garde-fou de cardinalité.
 */
class ParseRagMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final ParseRagMetrics metrics = TestMetrics.metrics(registry);

    @Test
    void parseCompletedTagsPlanOutcomeAndErrorCode() {
        metrics.parseCompleted(Plan.PRO, Outcome.SUCCESS, null, Duration.ofMillis(120));
        metrics.parseCompleted(Plan.FREE, Outcome.FAILURE, "PDF_UNREADABLE", Duration.ofMillis(30));

        assertEquals(1, registry.get(ParseRagMetrics.PARSE_TOTAL)
                .tags("plan", "pro", "outcome", "success", "error_code", "none").counter().count());
        assertEquals(1, registry.get(ParseRagMetrics.PARSE_TOTAL)
                .tags("plan", "free", "outcome", "failure", "error_code", "PDF_UNREADABLE")
                .counter().count());

        // La durée doit être relevée dans les deux cas : un échec lent est une information.
        assertEquals(2, registry.find(ParseRagMetrics.PARSE_DURATION).timers().size());
    }

    @Test
    void absentPlanIsCountedAsFree() {
        // Une requête sans clé tombe en FREE dans le pipeline : le tag doit suivre, jamais être vide.
        metrics.parseCompleted(null, Outcome.SUCCESS, null, Duration.ofMillis(1));

        assertEquals(1, registry.get(ParseRagMetrics.PARSE_TOTAL).tag("plan", "free")
                .counter().count());
    }

    @Test
    void stageTimesTheWorkAndReturnsItsValue() {
        String result = metrics.stage(Stage.EXTRACT, () -> "extracted");

        assertEquals("extracted", result, "l'instrumentation ne doit rien changer au résultat");
        assertEquals(1, registry.get(ParseRagMetrics.STAGE_DURATION)
                .tag("stage", "extract").timer().count());
    }

    @Test
    void stageRunnableVariantRunsExactlyOnce() {
        AtomicInteger runs = new AtomicInteger();
        metrics.stage(Stage.CLEAN, runs::incrementAndGet);

        assertEquals(1, runs.get());
        assertEquals(1, registry.get(ParseRagMetrics.STAGE_DURATION)
                .tag("stage", "clean").timer().count());
    }

    @Test
    void stagePropagatesFailureWithoutSwallowingIt() {
        // Un étage qui échoue doit remonter l'exception : l'observabilité n'est pas un filet.
        RuntimeException boom = new IllegalStateException("boom");
        try {
            metrics.stage(Stage.TABLES, () -> {
                throw boom;
            });
        } catch (IllegalStateException e) {
            assertEquals(boom, e);
        }
        assertEquals(1, registry.get(ParseRagMetrics.STAGE_DURATION)
                .tag("stage", "tables").timer().count(), "l'étage en échec reste chronométré");
    }

    @Test
    void authFailuresAreTaggedByReason() {
        metrics.authFailure(AuthFailure.MISSING_KEY);
        metrics.authFailure(AuthFailure.INVALID_KEY);
        metrics.authFailure(AuthFailure.INVALID_KEY);
        metrics.authFailure(AuthFailure.DB_UNAVAILABLE);

        assertEquals(1, counter(ParseRagMetrics.AUTH_FAILURES, "reason", "missing_key"));
        assertEquals(2, counter(ParseRagMetrics.AUTH_FAILURES, "reason", "invalid_key"));
        assertEquals(1, counter(ParseRagMetrics.AUTH_FAILURES, "reason", "db_unavailable"));
    }

    @Test
    void quotaUsageRatioIsRecordedAsAFraction() {
        metrics.quotaUsageRatio(Plan.STARTER, 250, 1000);

        assertEquals(0.25, registry.get(ParseRagMetrics.QUOTA_USAGE_RATIO)
                .tag("plan", "starter").summary().totalAmount(), 1e-9);
    }

    @Test
    void quotaUsageRatioIgnoresZeroLimit() {
        // Un plan à limite 0 diviserait par zéro : on n'émet rien plutôt qu'un Infinity.
        metrics.quotaUsageRatio(Plan.FREE, 5, 0);
        assertTrue(registry.find(ParseRagMetrics.QUOTA_USAGE_RATIO).summaries().isEmpty());
    }

    @Test
    void visionCallAndTokensCarryProviderAndModel() {
        metrics.visionCall("gemini", "gemini-3.5-flash-lite", Outcome.SUCCESS, Duration.ofMillis(900));
        metrics.visionTokens("gemini", "gemini-3.5-flash-lite", TokenType.PROMPT, 1_200);
        metrics.visionTokens("gemini", "gemini-3.5-flash-lite", TokenType.COMPLETION, 300);

        assertEquals(1, registry.get(ParseRagMetrics.VISION_CALLS)
                .tags("provider", "gemini", "model", "gemini-3.5-flash-lite", "outcome", "success")
                .counter().count());
        assertEquals(1_200, counter(ParseRagMetrics.VISION_TOKENS, "type", "prompt"));
        assertEquals(300, counter(ParseRagMetrics.VISION_TOKENS, "type", "completion"));
    }

    @Test
    void countersWithNothingToReportEmitNoSeries() {
        // Émettre des zéros créerait des séries inutiles, facturées, et brouillerait les graphes.
        metrics.headerFooterBlocks("geometry-recurrence", 0);
        metrics.headerFooterLines(0);
        metrics.tablesDetected(0);
        metrics.scannedPagesDetected(0);
        metrics.visionTokens("gemini", "m", TokenType.THOUGHTS, 0);

        assertTrue(registry.find(ParseRagMetrics.HF_BLOCKS).counters().isEmpty());
        assertTrue(registry.find(ParseRagMetrics.HF_LINES).counters().isEmpty());
        assertTrue(registry.find(ParseRagMetrics.TABLES_DETECTED).counters().isEmpty());
        assertTrue(registry.find(ParseRagMetrics.SCANNED_DETECTED).counters().isEmpty());
        assertTrue(registry.find(ParseRagMetrics.VISION_TOKENS).counters().isEmpty());
    }

    @Test
    void bucketsGaugeReflectsTheLiveValue() {
        AtomicInteger size = new AtomicInteger(3);
        metrics.registerBucketsGauge(size::get);

        assertEquals(3, registry.get(ParseRagMetrics.RATELIMIT_BUCKETS).gauge().value());
        size.set(7);
        assertEquals(7, registry.get(ParseRagMetrics.RATELIMIT_BUCKETS).gauge().value(),
                "la gauge doit suivre la source, pas figer sa valeur d'inscription");
    }

    /**
     * Le budget de cardinalité de l'issue tient à une propriété : aucun tag ne doit pouvoir porter
     * une valeur non bornée (clé API, identifiant, nom de fichier, email). Ici on vérifie qu'aucune
     * métrique métier n'expose de tag hors de la liste blanche.
     */
    @Test
    void noMetricCarriesAnUnboundedTag() {
        List<String> allowed = List.of("plan", "stage", "outcome", "error_code", "detector",
                "provider", "model", "type", "reason", "borderless",
                "error");   // ajouté par Micrometer sur les timers d'Observation, pas par nous

        metrics.stage(Stage.EXTRACT, () -> "x");
        metrics.parseCompleted(Plan.PRO, Outcome.SUCCESS, "X", Duration.ofMillis(1));
        metrics.documentParsed(Plan.PRO, 10, 2048, 12, 1);
        metrics.chunkConfidence(0.9);
        metrics.headerFooterBlocks("boilerplate", 2);
        metrics.headerFooterLines(4);
        metrics.tablesDetected(1);
        metrics.tableExtracted(true);
        metrics.scannedPagesDetected(2);
        metrics.authFailure(AuthFailure.MISSING_KEY);
        metrics.rateLimitRejected(Plan.FREE);
        metrics.quotaRejected(Plan.FREE);
        metrics.quotaUsageRatio(Plan.FREE, 1, 10);
        metrics.visionCall("gemini", "m", Outcome.SUCCESS, Duration.ofMillis(1));
        metrics.visionTokens("gemini", "m", TokenType.PROMPT, 5);
        metrics.visionBudgetExhausted();

        List<Meter> parseragMeters = registry.getMeters().stream()
                .filter(m -> m.getId().getName().startsWith("parserag."))
                .toList();
        assertTrue(parseragMeters.size() >= 14, "toutes les métriques métier doivent être posées");

        for (Meter meter : parseragMeters) {
            for (Tag tag : meter.getId().getTags()) {
                assertTrue(allowed.contains(tag.getKey()),
                        "tag hors liste blanche sur %s : %s".formatted(
                                meter.getId().getName(), tag.getKey()));
            }
        }
    }

    @Test
    void meterNamesAreAllPrefixed() {
        // Un préfixe commun rend le filtrage et le budget de séries lisibles côté Grafana.
        metrics.visionBudgetExhausted();
        assertNotNull(registry.find(ParseRagMetrics.VISION_BUDGET_OUT).counter());
        assertTrue(ParseRagMetrics.VISION_BUDGET_OUT.startsWith("parserag."));
    }

    private double counter(String name, String tagKey, String tagValue) {
        return registry.get(name).tag(tagKey, tagValue).counter().count();
    }
}
