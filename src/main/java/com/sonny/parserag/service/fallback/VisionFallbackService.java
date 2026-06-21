package com.sonny.parserag.service.fallback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.model.domain.TableResult;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Fallback d'extraction de tableau par vision (OpenAI GPT-4o mini), pour les tableaux <em>sans
 * bordures</em> que Tabula reconstruit mal (issue #9). On envoie l'<strong>image rognée de la
 * région</strong> du tableau et on demande une grille JSON stricte.
 *
 * <p><strong>Dégradation gracieuse :</strong> si la vision est désactivée ou la clé OpenAI absente
 * (cas dev, {@code OPENAI_API_KEY} vide), {@link #isAvailable()} est faux et {@link #extractTable}
 * renvoie {@code null} — jamais d'exception. L'appel HTTP est isolé ; le parsing de la réponse
 * ({@link #parseVisionContent}) est pur et testable hors-ligne.
 */
@Slf4j
@Service
public class VisionFallbackService {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    /** Confiance de base d'un tableau reconstruit par vision (calée sous le bordered Tabula). */
    private static final double VISION_CONFIDENCE = 0.6;

    private static final String SYSTEM_PROMPT = """
            You are a precise table extraction engine. You receive an image cropped to a single \
            table and return ONLY its content as strict JSON, no commentary.""";

    private static final String USER_PROMPT = """
            Extract the table in this image as JSON with exactly this shape:
            {"headers": ["col1", "col2", ...], "rows": [["c1", "c2", ...], ...]}
            Rules: one array per row, cells as plain strings (empty string if a cell is blank),
            keep the original reading order, do not invent or merge columns. If the image contains \
            no table, return {"headers": [], "rows": []}.""";

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Autowired
    public VisionFallbackService(AppProperties appProperties, ObjectMapper objectMapper) {
        this(appProperties, objectMapper, RestClient.create());
    }

    /** Constructeur testable (RestClient injectable). */
    VisionFallbackService(AppProperties appProperties, ObjectMapper objectMapper, RestClient restClient) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
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
            String content = callOpenAi(regionPng);
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

    /** Appel HTTP OpenAI (chat completions, vision). Renvoie le contenu texte du 1ᵉʳ choix. */
    private String callOpenAi(byte[] regionPng) {
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(regionPng);
        Map<String, Object> body = getStringObjectMap(dataUri);

        JsonNode response = restClient.post()
                .uri(OPENAI_URL)
                .header("Authorization", "Bearer " + appProperties.getOpenai().getApiKey())
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) return null;
        return response.path("choices").path(0).path("message").path("content").asText(null);
    }

    private @NonNull Map<String, Object> getStringObjectMap(String dataUri) {
        String model = appProperties.getOpenai().getModel();

        return Map.of(
                "model", model,
                "temperature", 0,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", List.of(
                                Map.of("type", "text", "text", USER_PROMPT),
                                Map.of("type", "image_url", "image_url", Map.of("url", dataUri))
                        ))
                )
        );
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
            List<String> headers = toStringList(root.path("headers"));
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode row : root.path("rows")) rows.add(toStringList(row));

            if (headers.isEmpty() && rows.isEmpty()) return null;

            int cols = Math.max(headers.size(), rows.stream().mapToInt(List::size).max().orElse(0));
            if (cols < 2) return null;
            int rowCount = rows.size() + (headers.isEmpty() ? 0 : 1);

            return new TableResult(page, caption, headers, rows, rowCount, cols, VISION_CONFIDENCE, true);
        } catch (Exception e) {
            log.warn("Vision response not parseable on page {}: {}", page, e.toString());
            return null;
        }
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
