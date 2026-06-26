package com.sonny.parserag.service.fallback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.model.domain.TableResult;
import com.sonny.parserag.model.domain.VisionPageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Fallback d'extraction par vision (OpenAI GPT-4o mini), sur deux usages :
 * <ul>
 *   <li>{@link #extractTable} — <strong>région de tableau</strong> sans bordures que Tabula
 *       reconstruit mal (issue #9) : image rognée → grille JSON stricte ;</li>
 *   <li>{@link #extractPage} — <strong>page scannée / image-only</strong> (issue #11) : image de la
 *       page entière → texte du corps + tableaux ({@link VisionPageResult}).</li>
 * </ul>
 *
 * <p><strong>Dégradation gracieuse :</strong> si la vision est désactivée ou la clé OpenAI absente
 * (cas dev, {@code OPENAI_API_KEY} vide), {@link #isAvailable()} est faux et les deux méthodes
 * renvoient {@code null} — jamais d'exception. L'appel HTTP est isolé ; le parsing des réponses
 * ({@link #parseVisionContent}, {@link #parsePageContent}) est pur et testable hors-ligne.
 */
@Slf4j
@Service
public class VisionFallbackService {

    /** Confiance de base d'un tableau reconstruit par vision (calée sous le bordered Tabula). */
    private static final double VISION_CONFIDENCE = 0.6;

    // ── Prompts « région de tableau » (issue #9) ──────────────────────────────────────────
    private static final String TABLE_SYSTEM_PROMPT = """
            You are a precise table extraction engine. You receive an image cropped to a single \
            table and return ONLY its content as strict JSON, no commentary.""";

    private static final String TABLE_USER_PROMPT = """
            Extract the table in this image as JSON with exactly this shape:
            {"headers": ["col1", "col2", ...], "rows": [["c1", "c2", ...], ...]}
            Rules: one array per row, cells as plain strings (empty string if a cell is blank).
            Every row MUST have exactly the same number of cells as "headers" — pad missing cells \
            with an empty string and never drop or merge cells. Keep dash/hyphen cells ("-") as \
            their own cell. Do not collapse a multi-level header into a single column.
            Keep the original reading order, do not invent columns. If the image contains no table, \
            return {"headers": [], "rows": []}.""";

    // ── Prompts « page scannée plein-page » (issue #11) ───────────────────────────────────
    private static final String PAGE_SYSTEM_PROMPT = """
            You are a precise document extraction engine. You receive an image of a single scanned \
            document page and return ONLY its content as strict JSON, no commentary.""";

    private static final String PAGE_USER_PROMPT = """
            Extract all text and tables from this document page as JSON with exactly this shape:
            {"text": "...", "tables": [{"headers": ["col1", ...], "rows": [["c1", ...], ...]}, ...]}
            For "text": clean reading-order paragraphs of the body text, excluding running \
            headers/footers and excluding any text that belongs to a table.
            For each table: one array per row, cells as plain strings (empty string if blank); \
            every row MUST have the same number of cells as its "headers".
            If the page has no tables, return "tables": []. If it has no body text, return "text": "".
            Respond ONLY with the JSON object.""";

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    /**
     * Client OpenAI officiel. Construit <em>paresseusement</em> au premier appel ({@link #client()}) :
     * en dev la clé est vide et {@link #isAvailable()} reste faux, donc on ne tente jamais de bâtir un
     * client sans clé. {@code volatile} + double-check pour la sûreté en concurrence.
     */
    private volatile OpenAIClient openAiClient;

    @Autowired
    public VisionFallbackService(AppProperties appProperties, ObjectMapper objectMapper) {
        this(appProperties, objectMapper, null);
    }

    /** Constructeur testable (client OpenAI injectable / mockable). */
    VisionFallbackService(AppProperties appProperties, ObjectMapper objectMapper, OpenAIClient openAiClient) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.openAiClient = openAiClient;
    }

    /** Construit (une fois) puis réutilise le client OpenAI à partir de la clé configurée. */
    private OpenAIClient client() {
        OpenAIClient local = openAiClient;
        if (local == null) {
            synchronized (this) {
                local = openAiClient;
                if (local == null) {
                    local = OpenAIOkHttpClient.builder()
                            .apiKey(appProperties.getOpenai().getApiKey())
                            .maxRetries(appProperties.getOpenai().getMaxRetries())
                            .build();
                    openAiClient = local;
                }
            }
        }
        return local;
    }

    /** Vrai si le fallback vision est activé ET une clé OpenAI est disponible. */
    public boolean isAvailable() {
        AppProperties.Vision v = appProperties.getVision();
        String key = appProperties.getOpenai().getApiKey();
        return v.isEnabled() && key != null && !key.isBlank();
    }

    /**
     * Reconstruit un tableau à partir de l'image (PNG) d'une région. Renvoie {@code null} si la vision
     * est indisponible, si l'appel échoue, ou si la réponse ne contient pas de tableau exploitable.
     */
    public TableResult extractTable(byte[] regionPng, int page, String caption) {
        if (!isAvailable() || regionPng == null || regionPng.length == 0) return null;

        try {
            String content = callOpenAi(TABLE_SYSTEM_PROMPT, TABLE_USER_PROMPT, regionPng);
            TableResult result = parseVisionContent(content, page, caption);
            if (result != null) {
                log.info("Vision fallback — page {}, {}x{} cells", page, result.rowCount(), result.colCount());
            }
            return result;
        } catch (Exception e) {
            log.warn("Vision fallback failed on page {}: {}", page, e.toString());
            return null;
        }
    }

    /**
     * Fallback vision <strong>plein-page</strong> pour une page scannée / image-only (issue #11) :
     * envoie l'image de la page entière et demande texte + tableaux. Renvoie {@code null} si la vision
     * est indisponible, si l'appel échoue, ou si la page ne contient rien d'exploitable.
     */
    public VisionPageResult extractPage(byte[] pagePng, int page) {
        if (!isAvailable() || pagePng == null || pagePng.length == 0) return null;

        try {
            String content = callOpenAi(PAGE_SYSTEM_PROMPT, PAGE_USER_PROMPT, pagePng);
            VisionPageResult result = parsePageContent(content, page);
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

    /**
     * Appel OpenAI via le SDK officiel (chat completions, vision) : message system (consigne) puis
     * message user multimodal = consigne texte + image PNG (data URI base64). Renvoie le contenu texte
     * du 1ᵉʳ choix, ou {@code null}.
     */
    private String callOpenAi(String systemPrompt, String userPrompt, byte[] png) {
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);

        ChatCompletionContentPart textPart = ChatCompletionContentPart.ofText(
                ChatCompletionContentPartText.builder().text(userPrompt).build());
        ChatCompletionContentPart imagePart = ChatCompletionContentPart.ofImageUrl(
                ChatCompletionContentPartImage.builder()
                        .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder().url(dataUri).build())
                        .build());

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(appProperties.getOpenai().getModel())
                .temperature(0d)
                .responseFormat(ResponseFormatJsonObject.builder().build())
                .addSystemMessage(systemPrompt)
                .addUserMessageOfArrayOfContentParts(List.of(textPart, imagePart))
                .build();

        ChatCompletion completion = client().chat().completions().create(params);
        return completion.choices().stream()
                .findFirst()
                .flatMap(choice -> choice.message().content())
                .orElse(null);
    }

    /**
     * Convertit le contenu JSON renvoyé par le modèle ({@code {"headers":[...],"rows":[[...]]}})
     * en {@link TableResult}. Tolère un éventuel enrobage Markdown (```json … ```). Renvoie
     * {@code null} si vide ou illisible. Méthode pure (sans HTTP).
     */
    TableResult parseVisionContent(String content, int page, String caption) {
        if (content == null || content.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(stripCodeFences(content));
            return buildTable(root, page, caption);
        } catch (Exception e) {
            log.warn("Vision response not parseable on page {}: {}", page, e.toString());
            return null;
        }
    }

    /**
     * Convertit le contenu JSON plein-page ({@code {"text": "...", "tables": [{headers,rows}, ...]}})
     * en {@link VisionPageResult}. Tolère l'enrobage Markdown. Renvoie {@code null} si la page est vide
     * (ni texte ni tableau) ou illisible. Méthode pure (sans HTTP).
     */
    VisionPageResult parsePageContent(String content, int page) {
        if (content == null || content.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(stripCodeFences(content));
            String text = root.path("text").asText("").strip();

            List<TableResult> tables = new ArrayList<>();
            for (JsonNode node : root.path("tables")) {
                String caption = node.hasNonNull("caption") ? node.path("caption").asText().strip() : null;
                TableResult tr = buildTable(node, page, caption);
                if (tr != null) tables.add(tr);
            }

            if (text.isBlank() && tables.isEmpty()) return null;
            return new VisionPageResult(page, text, tables);
        } catch (Exception e) {
            log.warn("Vision page response not parseable on page {}: {}", page, e.toString());
            return null;
        }
    }

    /**
     * Construit un {@link TableResult} à partir d'un nœud {@code {"headers":[...],"rows":[[...]]}}.
     * Rectangularise (en-tête et lignes alignés sur la largeur max, par complétion seule — jamais de
     * troncature → aucune donnée perdue). Renvoie {@code null} si vide ou à moins de 2 colonnes.
     */
    private TableResult buildTable(JsonNode node, int page, String caption) {
        List<String> headers = toStringList(node.path("headers"));
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode row : node.path("rows")) rows.add(toStringList(row));

        if (headers.isEmpty() && rows.isEmpty()) return null;

        int cols = Math.max(headers.size(), rows.stream().mapToInt(List::size).max().orElse(0));
        if (cols < 2) return null;

        while (headers.size() < cols) headers.add("");
        for (List<String> r : rows) while (r.size() < cols) r.add("");

        int rowCount = rows.size() + (headers.isEmpty() ? 0 : 1);
        return new TableResult(page, caption, headers, rows, rowCount, cols, VISION_CONFIDENCE, true);
    }

    private List<String> toStringList(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode cell : array) out.add(cell.isNull() ? "" : cell.asText("").strip());
        }
        return out;
    }

    /** Retire un éventuel bloc de code Markdown autour du JSON. */
    private String stripCodeFences(String s) {
        String t = s.strip();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
        }
        return t.strip();
    }
}
