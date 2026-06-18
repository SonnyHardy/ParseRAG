package com.sonny.parserag.config;

import com.sonny.parserag.entity.Plan;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    private final OpenAI               openai               = new OpenAI();
    private final Extraction           extraction           = new Extraction();
    private final Vision               vision               = new Vision();
    private final RateLimit            rateLimit            = new RateLimit();
    private final Quota                quota                = new Quota();
    private final Chunking             chunking             = new Chunking();
    private final PageLimits           pageLimits           = new PageLimits();
    private final HeaderFooterCleaning headerFooterCleaning = new HeaderFooterCleaning();

    @Data
    public static class OpenAI {
        private String apiKey;     // OPENAI_API_KEY
        private String model;
    }

    @Data
    public static class Extraction {
        /** Retire les colonnes de numéros de ligne en marge (copies de relecture/soumission). */
        private boolean stripLineNumbers;
    }

    @Data
    public static class Vision {
        private boolean enabled;
        @Min(0) @Max(1)
        private double  confidenceThreshold;
        @Positive
        private int     maxPagesPerDocument;
    }

    @Data
    public static class RateLimit {
        @Positive private int freeRequestsPerMinute;
        @Positive private int starterRequestsPerMinute;
        @Positive private int proRequestsPerMinute;
        @Positive private int scaleRequestsPerMinute;
    }

    @Data
    public static class Quota {
        @Positive private int freeMonthlyDocs;
        @Positive private int starterMonthlyDocs;
        @Positive private int proMonthlyDocs;
        @Positive private int scaleMonthlyDocs;
    }

    @Data
    public static class Chunking {
        @Positive private int maxChunkSize;
        @Positive private int overlap;
        @Positive private int minChunkSize;
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