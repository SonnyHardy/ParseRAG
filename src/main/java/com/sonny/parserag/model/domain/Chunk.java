package com.sonny.parserag.model.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Unité de texte extraite d'un document, prête à être
 * envoyée dans un vector store (Pinecone, Weaviate, Chroma…).
 * <p>
 * Chaque chunk porte un score de confiance (0.0 – 1.0)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Chunk(

        String id,               // ex: "chunk_doc8f3a_001"

        String text,             // texte extrait, nettoyé

        ChunkType type,          // PARAGRAPH, TABLE, etc.

        int page,                // numéro de page source (1-based)

        double confidence,       // fiabilité de l'extraction (0.0 – 1.0)

        @JsonProperty("fallback_used")
        boolean fallbackUsed,    // true si GPT-4o mini a été utilisé

        @JsonProperty("table_json")
        Object tableJson         // seulement si type = TABLE
) {

    /**
     * Factory pour un chunk texte standard (sans données de tableau).
     */
    public static Chunk of(
            String id,
            String text,
            ChunkType type,
            int page,
            double confidence
    ) {
        return new Chunk(id, text, type, page, confidence, false, null);
    }
}