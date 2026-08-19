package com.sonny.parserag.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sonny.parserag.model.domain.Chunk;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Réponse retournée par POST /api/v1/parse.
 * <p>
 * Évolue au fil des sprints :
 *   Sprint 1 → métadonnées + liste vide
 *   Sprint 2 → chunks avec texte et scores
 *   Sprint 3 → chunks de type TABLE avec table_json
 */
@Schema(name = "ParseResponse", description = "Result of a successful parse.")
public record ParseResponse(

        @JsonProperty("document_id")
        @Schema(description = "Identifier generated for this parse. Prefixes every chunk id.",
                example = "doc_9f3c1a7b")
        String documentId,

        @Schema(description = "Number of pages read. Capped by your plan.", example = "12")
        int pages,

        @Schema(description = "ISO 639-1 language code, taken from the PDF metadata when present "
                + "and otherwise guessed from stop words. One of fr, en, de, es, or unknown.",
                example = "en")
        String language,

        @JsonProperty("processing_ms")
        @Schema(description = "Server-side processing time in milliseconds.", example = "1843")
        long processingMs,

        @Schema(description = "Chunks in reading order, sorted by page. Ready to embed.")
        List<Chunk> chunks,

        @Schema(description = "Always \"ok\" on a 200. Failures are returned as an error object "
                + "with an HTTP error status.", example = "ok")
        String status
) {

    /** Factory — crée une réponse avec status = "ok". */
    public static ParseResponse ok(
            String documentId,
            int pages,
            String language,
            long processingMs,
            List<Chunk> chunks
    ) {
        return new ParseResponse(
                documentId, pages, language,
                processingMs, chunks, "ok"
        );
    }
}