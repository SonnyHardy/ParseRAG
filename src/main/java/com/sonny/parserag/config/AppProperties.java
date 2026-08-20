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

import java.util.LinkedHashMap;
import java.util.Map;

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
    private final Security             security             = new Security();
    private final Parse                parse                = new Parse();
    private final Health               health               = new Health();
    private final Gemini               gemini               = new Gemini();
    private final Extraction           extraction           = new Extraction();
    private final Tables               tables               = new Tables();
    private final Vision               vision               = new Vision();
    private final RapidApi             rapidapi             = new RapidApi();
    private final RateLimit            rateLimit            = new RateLimit();
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

    /** Fallback vision par défaut depuis l'issue #28 (Gemini Flash-Lite). */
    @Data
    public static class Gemini {
        /**
         * {@code GOOGLE_API_KEY} — nom lu par défaut par le SDK Google Gen AI.
         * ({@code GEMINI_API_KEY} est le nom <em>legacy</em> : on ne l'utilise pas.)
         */
        private String apiKey;
        /**
         * Tag stable, jamais une variante {@code -preview-*} ni un alias mouvant
         * ({@code gemini-flash-lite-latest}) : un modèle qui change ou disparaît fait basculer tout
         * un document en revue manuelle.
         * <p><strong>Ne pas retenter {@code gemini-2.5-flash-lite}</strong> (visé à l'ouverture de
         * l'issue #28, réexaminé en #48 pour son tarif 3 à 6 fois inférieur). Mesuré le 20/08/2026 :
         * {@code generateContent} répond <em>404 — « no longer available to new users. Please
         * update your code to use models/gemini-3.5-flash-lite »</em>. Toute la génération 2.5 est
         * fermée aux comptes qui ne l'utilisaient pas déjà ; ce n'est pas une question d'ancienneté
         * de compte ni de région, et aucune option de configuration ne la rouvre.
         * <p>Piège : {@code GET /v1beta/models/gemini-2.5-flash-lite} répond <em>200</em> et le
         * modèle figure au catalogue — seul l'appel de génération révèle le refus. Vérifier un accès
         * modèle par un {@code generateContent} réel, jamais par la lecture de métadonnées.
         */
        private String model = "gemini-3.5-flash-lite";
        /**
         * Effort de raisonnement : {@code minimal} (défaut), {@code low}, {@code medium},
         * {@code high}. La tâche est de la transcription structurée, pas du calcul — à ne relever
         * que si la qualité d'extraction le justifie, au prix de la latence et des tokens.
         * <p>Remplace le {@code thinkingBudget} de l'ère Gemini 2.5, que les modèles 3.x rejettent
         * (400 INVALID_ARGUMENT).
         */
        @NotBlank
        private String thinkingLevel = "minimal";
        /** Tentatives du SDK sur 429 (rate-limit) / 5xx, avec backoff exponentiel. */
        @Min(0)
        private int maxRetries = 4;
        /** Budget par appel, en <strong>millisecondes</strong> (unité attendue par {@code HttpOptions}). */
        @Positive
        private int timeoutMs = 60_000;
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
        /**
         * Fournisseur du fallback vision. Une seule implémentation depuis l'issue #58
         * ({@code gemini}) : le provider OpenAI, gardé le temps de comparer les deux sur le corpus
         * (issue #28), a été retiré une fois Gemini validé. La propriété reste — elle sélectionne le
         * bean par {@code @ConditionalOnProperty}, ce qui fait échouer le démarrage sur une valeur
         * inconnue au lieu de laisser le fallback silencieusement absent.
         */
        @NotBlank
        private String  provider = "gemini";
        @Min(0) @Max(1)
        private double  confidenceThreshold;
        /** Sous ce score de qualité sémantique d'une grille Tabula borderless, on bascule sur la vision. */
        @Min(0) @Max(1)
        private double  qualityThreshold;
        @Positive
        private int     maxPagesPerDocument;
    }

    /**
     * Identité de l'administrateur et bornes de sécurité (issue #56).
     */
    @Data
    public static class Security {
        /**
         * <strong>Hash SHA-256</strong> de la clé d'administration — jamais la clé elle-même.
         * Vide par défaut : aucune clé n'est alors créée, et {@code GET /api/v1/health} reste
         * inaccessible. Un défaut de configuration doit priver de diagnostic, pas fabriquer un
         * identifiant.
         */
        private String adminKeyHash = "";
        /** Propriétaire enregistré pour la clé d'administration ({@code owner_email} est unique). */
        @NotBlank
        private String adminKeyEmail = "admin@parserag.local";

        public boolean hasAdminKeyHash() {
            return adminKeyHash != null && !adminKeyHash.isBlank();
        }
    }

    /**
     * Bornes d'exécution du parsing (issue #56).
     * <p>
     * La ressource rare sur {@code /parse} n'est pas le débit mais la <strong>mémoire</strong> :
     * un upload de 50 Mo est chargé entier, le PDF est rouvert six fois par requête et le rendu
     * d'une page coûte ~9 Mo. Sans borne, les 200 threads Tomcat par défaut épuisent le tas bien
     * avant que quoi que ce soit ne les freine.
     */
    @Data
    public static class Parse {
        /** Parses simultanés autorisés. Au-delà, on refuse franchement plutôt que d'empiler. */
        @Positive
        private int maxConcurrent = 4;
        /**
         * Attente maximale d'une place avant refus. Une courte attente absorbe une rafale ; une
         * longue ne ferait que déplacer la file d'attente dans les threads du serveur.
         */
        @Min(0)
        private int maxWaitSeconds = 5;
    }

    /**
     * Intégration à la place de marché RapidAPI (issue #54), qui authentifie le consommateur,
     * applique le quota de son plan et facture. Notre backend ne fait plus que deux choses :
     * vérifier que la requête vient bien du proxy, et lire le plan qu'il annonce.
     */
    @Data
    public static class RapidApi {
        /**
         * Secret propre à notre API, ajouté par le proxy sur chaque requête
         * ({@code X-RapidAPI-Proxy-Secret}). Vide en dev : l'intégration est alors inactive et
         * seule la clé interne authentifie.
         */
        private String proxySecret = "";
        /**
         * Secret précédent, accepté en parallèle du courant. Sans ce second slot, toute rotation
         * de secret est une coupure : le proxy et le backend ne peuvent pas changer au même
         * instant.
         */
        private String previousProxySecret = "";
        /**
         * Correspondance {@code X-RapidAPI-Subscription} → {@link Plan}. Les noms de plans sont
         * définis dans le tableau de bord provider et peuvent changer sans redéploiement : ils
         * n'ont donc rien à faire en dur dans le code.
         * <p>Un nom absent de cette table retombe sur {@link Plan#FREE} — on échoue fermé.
         */
        private Map<String, Plan> planMapping = new LinkedHashMap<>();

        /** L'intégration n'est active que si un secret est configuré. */
        public boolean isConfigured() {
            return proxySecret != null && !proxySecret.isBlank();
        }

        /**
         * Plan correspondant au nom annoncé par le proxy, {@link Plan#FREE} par défaut.
         * <p>La casse est ignorée : {@code BASIC}, {@code Basic} et {@code basic} désignent le
         * même plan côté RapidAPI, et une différence de casse ne doit pas silencieusement
         * rétrograder un client payant.
         */
        public Plan planFor(String subscription) {
            if (subscription == null || subscription.isBlank()) return Plan.FREE;
            return planMapping.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(subscription.strip()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(Plan.FREE);
        }
    }

    /**
     * Garde de débit <strong>par consommateur</strong>, au débit de son plan (issues #14, #54).
     * <p>
     * RapidAPI cadence déjà ses abonnés ; ces paliers sont une seconde barrière et doivent rester
     * <em>au moins aussi hauts</em> que ceux du listing, sinon ce sont eux qui mordent en premier
     * et le client se voit refuser un débit qu'il a payé.
     */
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