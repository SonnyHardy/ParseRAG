package com.sonny.parserag.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sonny.parserag.model.domain.Chunk;

import java.util.List;

/**
 * Réponse retournée par POST /api/v1/parse.
 * <p>
 * Évolue au fil des sprints :
 *   Sprint 1 → métadonnées + liste vide
 *   Sprint 2 → chunks avec texte et scores
 *   Sprint 3 → chunks de type TABLE avec table_json
 */
public record ParseResponse(

        @JsonProperty("document_id")
        String documentId,

        int pages,

        String language,

        @JsonProperty("processing_ms")
        long processingMs,

        List<Chunk> chunks,

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