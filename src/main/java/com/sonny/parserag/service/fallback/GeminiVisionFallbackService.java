package com.sonny.parserag.service.fallback;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.Type;
import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.model.domain.TableResult;
import com.sonny.parserag.model.domain.VisionPageResult;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Fallback vision sur <strong>Gemini 2.5 Flash-Lite</strong> (issue #28) — implémentation par défaut
 * du port {@link VisionFallback}.
 *
 * <p><strong>Pourquoi ce fournisseur.</strong> La validation Phase 4 (#11) a montré que le facteur
 * limitant du fallback n'était pas notre code mais le plafond RPM/TPM du compte OpenAI, partagé par
 * tout le compte : sur un scan de 25 pages, seules 9 puis 12 pages passaient en vision, et le set de
 * pages réussies changeait d'un run à l'autre. Flash-Lite est positionné sur exactement notre profil
 * — beaucoup d'appels courts, structurés, à faible latence.
 *
 * <p><strong>Sortie contrainte.</strong> Chaque appel passe un {@code responseSchema} (structured
 * outputs) : la forme du JSON est garantie par l'API plutôt que seulement demandée en prose. Le
 * {@link VisionResponseParser} reste derrière en filet de sécurité. Le mode « thinking » est
 * explicitement désactivé ({@code thinkingBudget = 0}) : pour de l'OCR structuré il n'apporte rien
 * et coûte latence et tokens.
 *
 * <p><strong>Dégradation gracieuse</strong> (contrat du port) : vision désactivée ou clé absente
 * (cas dev, {@code GOOGLE_API_KEY} vide) → {@link #isAvailable()} faux et aucun client n'est bâti ;
 * appel en échec → {@code null}. Jamais d'exception vers l'appelant, qui bascule alors en
 * {@code manual_review_needed}.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "parserag.vision", name = "provider", havingValue = "gemini",
        matchIfMissing = true)
public class GeminiVisionFallbackService implements VisionFallback {

    private static final String PNG_MIME = "image/png";

    /** Codes HTTP retentés par le SDK : saturation (429), timeout amont (408), 5xx transitoires. */
    private static final Integer[] RETRYABLE_STATUS = {408, 429, 500, 502, 503, 504};

    // ── Schémas de sortie (structured outputs) ────────────────────────────────────────────
    private static final Schema STRING = Schema.builder().type(Type.Known.STRING).build();
    private static final Schema STRING_ARRAY =
            Schema.builder().type(Type.Known.ARRAY).items(STRING).build();

    /** {@code {"headers": [...], "rows": [[...], ...]}} — grille d'un tableau. */
    private static final Schema TABLE_SCHEMA = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(Map.of(
                    "headers", STRING_ARRAY,
                    "rows", Schema.builder().type(Type.Known.ARRAY).items(STRING_ARRAY).build()))
            .required("headers", "rows")
            .build();

    /** {@code {"text": "...", "tables": [ <table>, ... ]}} — page scannée entière. */
    private static final Schema PAGE_SCHEMA = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(Map.of(
                    "text", STRING,
                    "tables", Schema.builder().type(Type.Known.ARRAY).items(TABLE_SCHEMA).build()))
            .required("text", "tables")
            .build();

    /**
     * Point d'injection pour les tests. Le SDK ne se prête pas au mock : {@code Client} et
     * {@code Models} sont {@code final} et {@code models} est un <em>champ</em> public — un mock du
     * client laisserait ce champ à {@code null}. On isole donc l'appel réseau derrière cette
     * interface plutôt que derrière le type du SDK.
     */
    @FunctionalInterface
    interface GeminiCall {
        String generate(String systemPrompt, String userPrompt, byte[] png, Schema responseSchema);
    }

    private final AppProperties appProperties;
    private final VisionResponseParser parser;

    /** Non nul uniquement en test : court-circuite {@link #callGemini}. */
    private final GeminiCall call;

    /**
     * Client Gemini, construit <em>paresseusement</em> au premier appel ({@link #client()}) : en dev
     * la clé est vide et {@link #isAvailable()} reste faux, donc on ne tente jamais de bâtir un
     * client sans clé. {@code volatile} + double-check pour la sûreté en concurrence.
     */
    private volatile Client geminiClient;

    @Autowired
    public GeminiVisionFallbackService(AppProperties appProperties, VisionResponseParser parser) {
        this(appProperties, parser, null);
    }

    /** Constructeur testable (appel modèle stubbé, aucun réseau). */
    GeminiVisionFallbackService(AppProperties appProperties, VisionResponseParser parser, GeminiCall call) {
        this.appProperties = appProperties;
        this.parser = parser;
        this.call = call;
    }

    @Override
    public boolean isAvailable() {
        String key = appProperties.getGemini().getApiKey();
        return appProperties.getVision().isEnabled() && key != null && !key.isBlank();
    }

    @Override
    public TableResult extractTable(byte[] regionPng, int page, String caption) {
        if (!isAvailable() || regionPng == null || regionPng.length == 0) return null;

        try {
            String content = generate(VisionPrompts.TABLE_SYSTEM, VisionPrompts.TABLE_USER,
                    regionPng, TABLE_SCHEMA);
            TableResult result = parser.parseTable(content, page, caption);
            if (result != null) {
                log.info("Vision fallback — page {}, {}x{} cells", page, result.rowCount(), result.colCount());
            }
            return result;
        } catch (Exception e) {
            log.warn("Vision fallback failed on page {}: {}", page, e.toString());
            return null;
        }
    }

    @Override
    public VisionPageResult extractPage(byte[] pagePng, int page) {
        if (!isAvailable() || pagePng == null || pagePng.length == 0) return null;

        try {
            String content = generate(VisionPrompts.PAGE_SYSTEM, VisionPrompts.PAGE_USER,
                    pagePng, PAGE_SCHEMA);
            VisionPageResult result = parser.parsePage(content, page);
            if (result != null) {
                log.info("Vision page fallback — page {}, {} chars, {} table(s)",
                        page, result.text().length(), result.tables().size());
            }
            return result;
        } catch (Exception e) {
            log.warn("Vision page fallback failed on page {}: {}", page, e.toString());
            return null;
        }
    }

    /** Route vers l'appel stubbé (test) ou vers le vrai appel Gemini. */
    private String generate(String systemPrompt, String userPrompt, byte[] png, Schema schema) {
        GeminiCall stub = this.call;
        return stub != null
                ? stub.generate(systemPrompt, userPrompt, png, schema)
                : callGemini(systemPrompt, userPrompt, png, schema);
    }

    /**
     * Appel Gemini via le SDK officiel : consigne système + contenu multimodal (consigne texte +
     * image PNG), sortie contrainte par {@code schema}. Renvoie le texte de la réponse, ou
     * {@code null} si le modèle n'a rien produit.
     */
    private String callGemini(String systemPrompt, String userPrompt, byte[] png, Schema schema) {
        GenerateContentConfig config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(systemPrompt)))
                .temperature(0f)
                .responseMimeType("application/json")
                .responseSchema(schema)
                // Pas de raisonnement : la tâche est de la transcription structurée, pas du calcul.
                .thinkingConfig(ThinkingConfig.builder().thinkingBudget(0))
                .build();

        Content content = Content.fromParts(Part.fromText(userPrompt), Part.fromBytes(png, PNG_MIME));

        GenerateContentResponse response =
                client().models.generateContent(appProperties.getGemini().getModel(), content, config);

        // text() renvoie null sans candidat, et lève sur un finishReason inattendu (SAFETY,
        // MAX_TOKENS…) : les deux cas sont rattrapés par l'appelant → manual_review.
        return response.text();
    }

    /** Construit (une fois) puis réutilise le client Gemini à partir de la clé configurée. */
    private Client client() {
        Client local = geminiClient;
        if (local == null) {
            synchronized (this) {
                local = geminiClient;
                if (local == null) {
                    AppProperties.Gemini cfg = appProperties.getGemini();
                    local = Client.builder()
                            .apiKey(cfg.getApiKey())
                            .httpOptions(HttpOptions.builder()
                                    .timeout(cfg.getTimeoutMs())
                                    .retryOptions(HttpRetryOptions.builder()
                                            .attempts(cfg.getMaxRetries())
                                            .httpStatusCodes(List.of(RETRYABLE_STATUS)))
                                    .build())
                            .build();
                    geminiClient = local;
                    log.debug("Gemini client initialised — model: {}, timeout: {}ms, retries: {}",
                            cfg.getModel(), cfg.getTimeoutMs(), cfg.getMaxRetries());
                }
            }
        }
        return local;
    }

    /** Libère le pool de connexions HTTP du SDK à l'arrêt du contexte. */
    @PreDestroy
    void shutdown() {
        Client local = geminiClient;
        if (local != null) {
            try {
                local.close();
            } catch (Exception e) {
                log.debug("Gemini client close failed: {}", e.toString());
            }
        }
    }
}
