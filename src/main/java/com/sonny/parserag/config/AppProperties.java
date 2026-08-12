package com.sonny.parserag.config;

import com.sonny.parserag.entity.Plan;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Panneau de contrôle central de ParseRAG.
 * Toutes les propriétés préfixées par "parserag" dans application.yaml.
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "parserag")
public class AppProperties {

    private final Info                 info                 = new Info();
    private final Health               health               = new Health();
    private final OpenAI               openai               = new OpenAI();
    private final Extraction           extraction           = new Extraction();
    private final Tables               tables               = new Tables();
    private final Vision               vision               = new Vision();
    private final RateLimit            rateLimit            = new RateLimit();
    private final Quota                quota                = new Quota();
    private final Chunking             chunking             = new Chunking();
    private final Confidence           confidence           = new Confidence();
    private final PageLimits           pageLimits           = new PageLimits();
    private final HeaderFooterCleaning headerFooterCleaning = new HeaderFooterCleaning();

    /**
     * Identité de l'application, exposée par {@code GET /api/v1/health} (issue #37).
     * <p>
     * {@code version} est renseignée par le filtrage de ressources Maven ({@code @project.version@}
     * dans application.yaml) : elle suit donc automatiquement la version du pom.
     */
    @Data
    public static class Info {
        private String name    = "ParseRAG";
        private String version = "unknown";
    }

    /** Endpoint GET /api/v1/health (issue #37). */
    @Data
    public static class Health {
        /**
         * Borne (secondes) appliquée à <em>chaque</em> sonde qui passe par la base (ping
         * {@code SELECT 1} et lecture de {@code flyway_schema_history}). Sans elle, une base
         * injoignable ferait attendre le health check jusqu'au
         * {@code spring.datasource.hikari.connection-timeout} — inexploitable par un monitoring.
         * Au-delà de cette borne, la sonde concernée est déclarée {@code DOWN}.
         */
        @Positive
        private int dbPingTimeoutSeconds = 3;

        /**
         * Chemin dont on surveille l'espace libre. Par défaut le répertoire de travail : sur un
         * déploiement mono-volume c'est le même système de fichiers que le temp servlet où
         * atterrissent les uploads. À pointer explicitement vers {@code java.io.tmpdir} si les
         * deux vivent sur des volumes distincts.
         */
        @NotBlank
        private String diskPath = ".";

        /**
         * Plancher d'espace libre (Mo) sous lequel {@code disk} passe {@code DOWN}. Un upload fait
         * jusqu'à 50 Mo et PDFBox écrit des fichiers temporaires : bien en dessous de ce plancher,
         * le parsing échouerait sur un disque plein plutôt que sur une erreur métier.
         */
        @Positive
        private long minFreeDiskMb = 500;
    }

    @Data
    public static class OpenAI {
        private String apiKey;     // OPENAI_API_KEY
        private String model;
        /** Tentatives du SDK sur 429 (rate-limit) / 5xx, avec backoff exponentiel. */
        @Min(0)
        private int maxRetries = 4;
    }

    @Data
    public static class Extraction {
        /** Retire les colonnes de numéros de ligne en marge (copies de relecture/soumission). */
        private boolean stripLineNumbers;
    }

    @Data
    public static class Tables {
        private boolean enabled;
        /** Active le fallback Tabula sans bordures (BasicExtractionAlgorithm) sur pages tabulaires. */
        private boolean borderlessFallback;
        /** Filtrage anti-faux-positifs : seuils minimaux d'un vrai tableau. */
        @Positive private int    minRows;
        @Positive private int    minCols;
        @Min(0) @Max(1)
        private double minFillRatio;   // ratio minimal de cellules non-vides
    }

    @Data
    public static class Vision {
        private boolean enabled;
        @Min(0) @Max(1)
        private double  confidenceThreshold;
        /** Sous ce score de qualité sémantique d'une grille Tabula borderless, on bascule sur la vision. */
        @Min(0) @Max(1)
        private double  qualityThreshold;
        @Positive
        private int     maxPagesPerDocument;
    }

    @Data
    public static class RateLimit {
        @Positive private int freeRequestsPerMinute;
        @Positive private int starterRequestsPerMinute;
        @Positive private int proRequestsPerMinute;
        @Positive private int scaleRequestsPerMinute;

        /** Débit autorisé (requêtes/minute) pour le plan donné — capacité du token bucket. */
        public int forPlan(Plan plan) {
            return switch (plan) {
                case FREE    -> freeRequestsPerMinute;
                case STARTER -> starterRequestsPerMinute;
                case PRO     -> proRequestsPerMinute;
                case SCALE   -> scaleRequestsPerMinute;
            };
        }
    }

    @Data
    public static class Quota {
        @Positive private int freeMonthlyDocs;
        @Positive private int starterMonthlyDocs;
        @Positive private int proMonthlyDocs;
        @Positive private int scaleMonthlyDocs;

        public int forPlan(Plan plan) {
            return switch (plan) {
                case FREE    -> freeMonthlyDocs;
                case STARTER -> starterMonthlyDocs;
                case PRO     -> proMonthlyDocs;
                case SCALE   -> scaleMonthlyDocs;
            };
        }
    }

    @Data
    public static class Chunking {
        @Positive private int maxChunkSize;
        @Positive private int overlap;
        @Positive private int minChunkSize;
    }

    /**
     * Score de confiance « reading-order » (issue #30, palier 1). La pénalité d'ordre de
     * lecture mesure les traces textuelles d'un entrelacement de colonnes (espaces internes
     * larges, césures suivies d'un espace) et module le score « forme » de façon multiplicative.
     */
    @Data
    public static class Confidence {
        // ── Palier 1 : pénalité textuelle par chunk (traces intra-ligne d'entrelacement) ──
        /** Taux d'anomalies/ligne à partir duquel la pénalité d'ordre de lecture atteint son plancher. */
        @Positive
        private double maxAnomalyRate;
        /** Plancher de la pénalité : un chunk désordonné ne tombe jamais à 0 sur ce seul signal. */
        @Min(0) @Max(1)
        private double penaltyFloor;

        // ── Palier 3 : score géométrique par chunk (lignes suspectes attribuées au chunk) ──
        /** Fraction de lignes suspectes/chunk à partir de laquelle le score d'ordre de lecture du chunk atteint son plancher. */
        @Positive
        private double maxSuspectLineRate;
        /** Plancher du facteur d'ordre de lecture par chunk : un chunk entrelacé ne tombe jamais à 0 sur ce seul signal. */
        @Min(0) @Max(1)
        private double readingOrderFloor;
        /** Confidence finale en deçà de laquelle le chunk est marqué {@code manual_review_needed}. */
        @Min(0) @Max(1)
        private double manualReviewThreshold;
    }

    /** Nettoyage header/footer en couches (cf. package service.headerfooter). */
    @Data
    public static class HeaderFooterCleaning {
        private boolean enabled;

        @Positive
        private double blockVerticalGapPt;     // tolérance Y (pt) pour grouper les fragments d'une même ligne
        @Min(0) @Max(1)
        private double headerZoneRatio;        // un bloc est header SI y0/H < headerZoneRatio
        @Min(0) @Max(1)
        private double footerZoneRatio;        // un bloc est footer SI y1/H > 1 - footerZoneRatio
        @Positive
        private int    minRecurrentPages;      // couche récurrence : un pattern doit recurrer sur ≥ N pages (cappé à pageCount)

        // ── Couche DBSCAN (secondaire) ──────────────────────────────────────
        @Positive
        private double dbscanEps;              // rayon de voisinage (features page-normalized dans [0,1])
        @Positive
        private int    dbscanMinSamples;       // taille min d'un noyau de cluster
    }

    @Data
    public static class PageLimits {
        @Positive private int maxPagesFree;
        @Positive private int maxPagesStarter;
        @Positive private int maxPagesPro;
        @Positive private int maxPagesScale;

        public int forPlan(Plan plan) {
            return switch (plan) {
                case FREE    -> maxPagesFree;
                case STARTER -> maxPagesStarter;
                case PRO     -> maxPagesPro;
                case SCALE   -> maxPagesScale;
            };
        }
    }
}