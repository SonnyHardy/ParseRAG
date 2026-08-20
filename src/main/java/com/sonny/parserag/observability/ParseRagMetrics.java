package com.sonny.parserag.observability;

import com.sonny.parserag.entity.Plan;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Point d'entrée <strong>unique</strong> de l'instrumentation métier (issue #38). Tous les noms de
 * métriques et tous les tags sont définis ici : c'est le seul fichier à relire pour vérifier le
 * budget de cardinalité, plutôt que d'auditer chaque appel dispersé dans les services.
 *
 * <p><strong>Cardinalité bornée par construction.</strong> Les tags ne sont pas des {@code String}
 * libres mais des énumérations ({@link Stage}, {@link Outcome}, {@link AuthFailure},
 * {@link TokenType}) ou {@link Plan}. Un appelant ne <em>peut</em> donc pas y glisser une clé API,
 * un identifiant de clé, un nom de fichier ou un email — ce qui ferait exploser le nombre de séries
 * et, pour les deux derniers, exfiltrerait de la donnée personnelle vers le backend de métriques.
 * Le seul tag textuel est {@code error_code}, borné par les codes de {@code ParseRagException} et
 * normalisé par {@link #errorCode}.
 *
 * <p><strong>Étages du pipeline : une seule instrumentation pour deux signaux.</strong>
 * {@link #stage} passe par l'{@link Observation} de Micrometer, qui produit à la fois le timer
 * {@code parserag.stage.duration} et le span de la trace. Poser un {@code Timer} et un {@code Span}
 * séparément, c'est deux fois le code pour deux vérités qui finissent par diverger.
 * Micrometer ajoute de lui-même un tag {@code error} à ce timer ({@code none} ou le nom simple de
 * l'exception) : il est utile — il sépare les étages qui ont échoué — mais il est le seul tag que
 * cette classe ne contrôle pas, et sa cardinalité suit le nombre de types d'exception atteignables.
 *
 * <p>Aucun garde à porter côté appelants : si l'export OTLP est coupé ({@code OTEL_ENABLED} absent),
 * Micrometer enregistre quand même dans un registre en mémoire — les compteurs restent lisibles via
 * {@code /actuator/metrics} et assertables en test.
 */
@Component
public class ParseRagMetrics {

    // ── Noms de métriques ─────────────────────────────────────────────────────────────────
    public static final String PARSE_TOTAL        = "parserag.parse.total";
    public static final String PARSE_DURATION     = "parserag.parse.duration";
    public static final String STAGE_DURATION     = "parserag.stage.duration";
    public static final String DOCUMENT_PAGES     = "parserag.document.pages";
    public static final String DOCUMENT_BYTES     = "parserag.document.bytes";
    public static final String CHUNKS_PRODUCED    = "parserag.chunks.produced";
    public static final String CHUNK_CONFIDENCE   = "parserag.chunk.confidence";
    public static final String CHUNKS_REVIEW      = "parserag.chunks.manual_review";
    public static final String HF_BLOCKS          = "parserag.headerfooter.blocks_confirmed";
    public static final String HF_LINES           = "parserag.headerfooter.lines_stripped";
    public static final String TABLES_DETECTED    = "parserag.tables.detected";
    public static final String TABLES_EXTRACTED   = "parserag.tables.extracted";
    public static final String SCANNED_DETECTED   = "parserag.scanned.detected";
    public static final String AUTH_FAILURES      = "parserag.auth.failures";
    public static final String RATELIMIT_REJECTED = "parserag.ratelimit.rejected";
    public static final String RATELIMIT_BUCKETS  = "parserag.ratelimit.buckets_active";
    public static final String PARSE_SLOTS_FREE  = "parserag.parse.slots_free";
    public static final String PARSE_REJECTED_BUSY = "parserag.parse.rejected_busy";
    public static final String VISION_CALLS       = "parserag.vision.calls";
    public static final String VISION_DURATION    = "parserag.vision.duration";
    public static final String VISION_TOKENS      = "parserag.vision.tokens";
    public static final String VISION_BUDGET_OUT  = "parserag.vision.budget_exhausted";

    // ── Tags (fermés) ─────────────────────────────────────────────────────────────────────

    /** Étages du pipeline — sert de tag {@code stage} et de nom de span. */
    public enum Stage {
        EXTRACT, CLEAN, TABLES, CHUNK, SCANNED;

        String tag() {
            return name().toLowerCase();
        }
    }

    public enum Outcome {
        SUCCESS, FAILURE;

        String tag() {
            return name().toLowerCase();
        }
    }

    /** Les trois branches d'échec de {@code ApiKeyFilter}. */
    public enum AuthFailure {
        MISSING_KEY, INVALID_KEY, DB_UNAVAILABLE,
        /**
         * Clé interne valide mais non-admin, présentée en direct alors que l'intégration RapidAPI
         * est active : quelqu'un tente de servir l'origine sans passer par la place de marché
         * (issue #56).
         */
        MARKETPLACE_REQUIRED,
        /**
         * Secret proxy absent des réglages ou faux : quelqu'un appelle l'origine sans passer par
         * la place de marché. Ce n'est pas une métrique technique mais un signal de contournement
         * commercial — à surveiller comme tel (issue #54).
         */
        INVALID_PROXY_SECRET;

        String tag() {
            return name().toLowerCase();
        }
    }

    public enum TokenType {
        PROMPT, COMPLETION, THOUGHTS;

        String tag() {
            return name().toLowerCase();
        }
    }

    /** Marqueur d'absence de code d'erreur : un tag vide casserait certaines requêtes PromQL. */
    private static final String NO_ERROR = "none";

    private final MeterRegistry registry;
    private final ObservationRegistry observationRegistry;

    public ParseRagMetrics(MeterRegistry registry, ObservationRegistry observationRegistry) {
        this.registry = registry;
        this.observationRegistry = observationRegistry;
    }

    // ── Pipeline ──────────────────────────────────────────────────────────────────────────

    /**
     * Exécute un étage du pipeline en le chronométrant et en ouvrant un span. Le nom du span
     * ({@code parse.extract}, …) est distinct du nom du timer : sans {@code contextualName}, la
     * trace afficherait « parserag.stage.duration » sur chaque span, illisible.
     */
    public <T> T stage(Stage stage, Supplier<T> work) {
        return Observation.createNotStarted(STAGE_DURATION, observationRegistry)
                .lowCardinalityKeyValue("stage", stage.tag())
                .contextualName("parse." + stage.tag())
                .observe(work);
    }

    /** Variante sans valeur de retour. */
    public void stage(Stage stage, Runnable work) {
        stage(stage, () -> {
            work.run();
            return null;
        });
    }

    /** Issue d'un parse : compteur taggé + durée totale. */
    public void parseCompleted(Plan plan, Outcome outcome, String errorCode, Duration duration) {
        Counter.builder(PARSE_TOTAL)
                .tag("plan", planTag(plan))
                .tag("outcome", outcome.tag())
                .tag("error_code", errorCode(errorCode))
                .register(registry)
                .increment();

        Timer.builder(PARSE_DURATION)
                .tag("plan", planTag(plan))
                .tag("outcome", outcome.tag())
                .register(registry)
                .record(duration);
    }

    /** Volumétrie du document traité et de sa sortie. */
    public void documentParsed(Plan plan, int pages, long bytes, int chunks, int manualReview) {
        summary(DOCUMENT_PAGES, plan).record(pages);
        summary(DOCUMENT_BYTES, plan).record(bytes);
        summary(CHUNKS_PRODUCED, plan).record(chunks);
        if (manualReview > 0) {
            registry.counter(CHUNKS_REVIEW).increment(manualReview);
        }
    }

    /** Distribution des confiances de chunk — alimente le suivi qualité (#8/#30). */
    public void chunkConfidence(double confidence) {
        DistributionSummary.builder(CHUNK_CONFIDENCE).register(registry).record(confidence);
    }

    // ── Étages spécialisés ────────────────────────────────────────────────────────────────

    /**
     * Blocs confirmés par une couche header/footer. Volontairement des <em>blocs</em> et non des
     * lignes : {@code LineStripper} retire les lignes en une passe globale sur l'union des
     * confirmations, donc aucune ligne n'est attribuable à un détecteur en particulier.
     */
    public void headerFooterBlocks(String detector, int blocks) {
        if (blocks > 0) {
            registry.counter(HF_BLOCKS, "detector", detector).increment(blocks);
        }
    }

    /** Total de lignes retirées, tous détecteurs confondus. */
    public void headerFooterLines(int lines) {
        if (lines > 0) registry.counter(HF_LINES).increment(lines);
    }

    public void tablesDetected(int count) {
        if (count > 0) registry.counter(TABLES_DETECTED).increment(count);
    }

    public void tableExtracted(boolean borderless) {
        registry.counter(TABLES_EXTRACTED, "borderless", String.valueOf(borderless)).increment();
    }

    public void scannedPagesDetected(int count) {
        if (count > 0) registry.counter(SCANNED_DETECTED).increment(count);
    }

    // ── Auth / rate limit / quota ─────────────────────────────────────────────────────────

    public void authFailure(AuthFailure reason) {
        registry.counter(AUTH_FAILURES, "reason", reason.tag()).increment();
    }

    /**
     * Rejet par la garde de débit, tagué par plan : c'est ce qui distingue un palier trop juste
     * pour un tier donné d'un client isolé qui s'emballe. Pas de tag d'identité — sa cardinalité
     * serait non bornée ; la gauge ci-dessous dit combien d'appelants sont suivis.
     */
    public void rateLimitRejected(Plan plan) {
        registry.counter(RATELIMIT_REJECTED, "plan", planTag(plan)).increment();
    }

    /**
     * Places de parsing encore libres (issue #56). C'est l'indicateur de saturation : une valeur
     * qui reste à zéro annonce les refus avant que le client ne les subisse.
     */
    public void registerParseSlotsGauge(Supplier<Number> free) {
        Gauge.builder(PARSE_SLOTS_FREE, free).register(registry);
    }

    /** Parse refusé faute de place — distinct d'un rejet de débit : ici l'origine est pleine. */
    public void parseRejectedBusy() {
        registry.counter(PARSE_REJECTED_BUSY).increment();
    }

    /** Nombre de buckets de rate limiting vivants — empreinte mémoire suivie par #35. */
    public void registerBucketsGauge(Supplier<Number> size) {
        Gauge.builder(RATELIMIT_BUCKETS, size).register(registry);
    }

    // ── Fallback vision ───────────────────────────────────────────────────────────────────

    /** Un appel au modèle de vision : issue + durée, tous fournisseurs confondus. */
    public void visionCall(String provider, String model, Outcome outcome, Duration duration) {
        Counter.builder(VISION_CALLS)
                .tag("provider", provider)
                .tag("model", model)
                .tag("outcome", outcome.tag())
                .register(registry)
                .increment();

        Timer.builder(VISION_DURATION)
                .tag("provider", provider)
                .tag("model", model)
                .register(registry)
                .record(duration);
    }

    /** Tokens facturés, par nature — c'est la métrique de coût. */
    public void visionTokens(String provider, String model, TokenType type, int tokens) {
        if (tokens > 0) {
            registry.counter(VISION_TOKENS, "provider", provider, "model", model, "type", type.tag())
                    .increment(tokens);
        }
    }

    /** Le cap vision par document a été atteint : des pages partent en revue manuelle. */
    public void visionBudgetExhausted() {
        registry.counter(VISION_BUDGET_OUT).increment();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────

    private DistributionSummary summary(String name, Plan plan) {
        return DistributionSummary.builder(name).tag("plan", planTag(plan)).register(registry);
    }

    /** Un plan absent (requête sans clé) est compté comme FREE, comme le fait le pipeline. */
    private static String planTag(Plan plan) {
        return (plan != null ? plan : Plan.FREE).name().toLowerCase();
    }

    private static String errorCode(String code) {
        return code == null || code.isBlank() ? NO_ERROR : code;
    }
}
