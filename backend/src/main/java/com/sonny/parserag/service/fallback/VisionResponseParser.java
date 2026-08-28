package com.sonny.parserag.service.fallback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonny.parserag.model.domain.TableResult;
import com.sonny.parserag.model.domain.VisionPageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Traduit la réponse JSON d'un modèle de vision en modèle de domaine. <strong>Pur</strong> (aucun
 * réseau) et <strong>indépendant du fournisseur</strong> : c'est le socle partagé par
 * {@link GeminiVisionFallbackService} et {@link OpenAiVisionFallbackService} (issue #28).
 *
 * <p>Reste un filet de sécurité même quand le fournisseur garantit la forme de la sortie
 * (structured outputs côté Gemini) : un modèle peut toujours enrober son JSON dans un bloc de code
 * Markdown, omettre une cellule, ou rendre une grille dégénérée.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisionResponseParser {

    /** Confiance de base d'un tableau reconstruit par vision (calée sous le bordered Tabula). */
    static final double VISION_CONFIDENCE = 0.6;

    private final ObjectMapper objectMapper;

    /**
     * Convertit le contenu JSON d'une région de tableau ({@code {"headers":[...],"rows":[[...]]}})
     * en {@link TableResult}. Tolère un éventuel enrobage Markdown (```json … ```). Renvoie
     * {@code null} si vide ou illisible.
     */
    public TableResult parseTable(String content, int page, String caption) {
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
     * en {@link VisionPageResult}. Tolère l'enrobage Markdown. Renvoie {@code null} si la page est
     * vide (ni texte ni tableau) ou illisible.
     */
    public VisionPageResult parsePage(String content, int page) {
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
