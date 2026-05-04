package com.sonny.parserag.model.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Unité de texte extraite d'un document, prête à être
 * envoyée dans un vector store (Pinecone, Weaviate, Chroma…).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Chunk(

        String id,

        String text,

        ChunkType type,

        int page,

        @JsonProperty("char_start")
        int charStart,

        @JsonProperty("char_end")
        int charEnd,

        double confidence,

        @JsonProperty("fallback_used")
        boolean fallbackUsed,

        @JsonProperty("table_json")
        Object tableJson
) {

    /** Factory pour un chunk texte standard (sans données de tableau). */
    public static Chunk of(
            String id,
            String text,
            ChunkType type,
            int page,
            int charStart,
            int charEnd,
            double confidence
    ) {
        return new Chunk(id, text, type, page, charStart, charEnd, confidence, false, null);
    }
}
