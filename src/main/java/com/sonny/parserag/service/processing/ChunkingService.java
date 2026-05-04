package com.sonny.parserag.service.processing;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.model.domain.Chunk;
import com.sonny.parserag.model.domain.ChunkType;
import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.model.domain.ExtractedPage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkingService {

    private final AppProperties appProperties;

    public List<Chunk> chunk(ExtractedDocument doc) {
        int maxChunkSize = appProperties.getChunking().getMaxChunkSize();
        int overlap      = appProperties.getChunking().getOverlap();
        int minChunkSize = appProperties.getChunking().getMinChunkSize();

        List<Chunk> chunks = new ArrayList<>();
        int[] counter = {0};

        for (ExtractedPage page : doc.pages()) {
            if (page.rawText() == null || page.rawText().isBlank()) continue;
            chunkPage(page, doc.documentId(), counter, chunks, maxChunkSize, overlap, minChunkSize);
        }

        log.info("Chunking complete — docId: {}, chunks: {}", doc.documentId(), chunks.size());
        return chunks;
    }

    private void chunkPage(ExtractedPage page, String docId, int[] counter,
                           List<Chunk> result, int maxChunkSize, int overlap, int minChunkSize) {
        String text = page.rawText();
        int pos = 0;
        int len = text.length();

        while (pos < len) {
            // Sauter les sauts de ligne en tête de paragraphe
            while (pos < len && text.charAt(pos) == '\n') pos++;
            if (pos >= len) break;

            int sepIdx  = text.indexOf("\n\n", pos);
            int paraEnd = (sepIdx == -1) ? len : sepIdx;

            String para = text.substring(pos, paraEnd).strip();

            if (para.length() >= minChunkSize) {
                int paraOffset = text.indexOf(para, pos);

                if (para.length() <= maxChunkSize) {
                    addChunk(result, docId, counter, para, ChunkType.PARAGRAPH,
                            page.pageNumber(), paraOffset, paraOffset + para.length());
                } else {
                    splitBySentences(para, paraOffset, page.pageNumber(),
                            docId, counter, result, maxChunkSize, overlap, minChunkSize);
                }
            }

            pos = (sepIdx == -1) ? len : sepIdx + 2;
        }
    }

    /**
     * Découpe un paragraphe trop long en chunks de phrases avec overlap.
     * <p>
     * Agrège des phrases jusqu'à maxChunkSize, puis flush le chunk courant
     * et réinjecte les `overlap` derniers caractères en tête du suivant.
     */
    private void splitBySentences(String para, int paraOffset, int pageNumber,
                                   String docId, int[] counter, List<Chunk> result,
                                   int maxChunkSize, int overlap, int minChunkSize) {
        // Split après ". " pour conserver la ponctuation dans chaque chunk
        String[]      sentences  = para.split("(?<=\\. )");
        StringBuilder current    = new StringBuilder();
        int           chunkStart = 0; // début du chunk courant dans para

        for (String sentence : sentences) {
            if (current.length() + sentence.length() > maxChunkSize && !current.isEmpty()) {
                // ── Flush ──────────────────────────────────────────────────────
                if (current.length() >= minChunkSize) {
                    String txt = current.toString().strip();
                    addChunk(result, docId, counter, txt, ChunkType.PARAGRAPH, pageNumber,
                            paraOffset + chunkStart,
                            paraOffset + chunkStart + current.length());
                }

                // ── Overlap : les N derniers chars deviennent le début du suivant
                int overlapStart = Math.max(0, current.length() - overlap);
                chunkStart = chunkStart + overlapStart;
                current    = new StringBuilder(current.substring(overlapStart));
            }

            current.append(sentence);
        }

        // Flush du dernier segment
        if (current.length() >= minChunkSize) {
            String txt = current.toString().strip();
            addChunk(result, docId, counter, txt, ChunkType.PARAGRAPH, pageNumber,
                    paraOffset + chunkStart,
                    paraOffset + chunkStart + current.length());
        }
    }

    private void addChunk(List<Chunk> result, String docId, int[] counter,
                          String text, ChunkType type, int page, int charStart, int charEnd) {
        String id = "chunk_%s_%03d".formatted(docId, counter[0]++);
        result.add(Chunk.of(id, text, type, page, charStart, charEnd, 1.0));  // Todo: confidence à calculer lors de l'issue #8
    }
}
