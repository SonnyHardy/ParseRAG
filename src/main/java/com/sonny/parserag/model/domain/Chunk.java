package com.sonny.parserag.model.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Unité de texte extraite d'un document, prête à être
 * envoyée dans un vector store (Pinecone, Weaviate, Chroma…).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "Chunk", description = "A unit of text ready to embed into a vector store.")
public record Chunk(

        @Schema(description = "Stable id, sequential within the document.",
                example = "chunk_doc_9f3c1a7b_004")
        String id,

        @Schema(description = "The chunk text. For a TABLE chunk, the table linearised as "
                + "pipe-separated rows, so it can be embedded as-is.")
        String text,

        @Schema(description = "What this chunk holds.")
        ChunkType type,

        @Schema(description = "1-based page the chunk was taken from.", example = "3")
        int page,

        @JsonProperty("char_start")
        @Schema(description = "Start offset of the chunk in the page text. 0 for TABLE chunks.",
                example = "0")
        int charStart,

        @JsonProperty("char_end")
        @Schema(description = "End offset of the chunk in the page text. 0 for TABLE chunks.",
                example = "1874")
        int charEnd,

        @Schema(description = "Extraction confidence between 0 and 1. Penalised when the reading "
                + "order looks interleaved or the text carries extraction artefacts.",
                example = "0.94", minimum = "0", maximum = "1")
        double confidence,

        @JsonProperty("fallback_used")
        @Schema(description = "True when a vision model produced this chunk, because the page was "
                + "scanned or the table grid was unusable.", example = "false")
        boolean fallbackUsed,

        @JsonProperty("manual_review_needed")
        @Schema(description = "True when confidence fell below the review threshold. Treat these "
                + "chunks as suspect rather than indexing them blindly.", example = "false")
        boolean manualReviewNeeded,

        @JsonProperty("table_json")
        @Schema(implementation = java.util.Map.class,
                description = "Structured table, present only on TABLE chunks and omitted "
                        + "otherwise. Holds `headers` (array of strings), `rows` (array of row "
                        + "arrays) and, when one was found, `caption`.",
                example = """
                        {"caption": "Table 2: Accuracy by model",                         "headers": ["Model", "SNLI", "MultiNLI"],                         "rows": [["ESIM+GloVe", "51.9", "52.7"], ["BERT-base", "56.3", "58.1"]]}""")
        Object tableJson
) {

    /** Factory pour un chunk texte standard (sans données de tableau, sans revue manuelle). */
    public static Chunk of(
            String id,
            String text,
            ChunkType type,
            int page,
            int charStart,
            int charEnd,
            double confidence
    ) {
        return new Chunk(id, text, type, page, charStart, charEnd, confidence, false, false, null);
    }
}
