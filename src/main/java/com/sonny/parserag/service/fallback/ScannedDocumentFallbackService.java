package com.sonny.parserag.service.fallback;

import com.sonny.parserag.model.domain.Chunk;
import com.sonny.parserag.model.domain.ChunkType;
import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.model.domain.ExtractedPage;
import com.sonny.parserag.model.domain.TableResult;
import com.sonny.parserag.model.domain.VisionPageResult;
import com.sonny.parserag.service.processing.ChunkingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Fallback vision <strong>plein-page</strong> pour les pages scannées / image-only (issue #11).
 * Pour chaque page repérée par {@code ScannedPageDetector}, rend la page entière en PNG, délègue à
 * {@link VisionFallback#extractPage} (texte + tableaux), puis mappe le résultat en chunks :
 * le texte est redécoupé via {@link ChunkingService} (dimensionnement RAG cohérent) en chunks
 * {@link ChunkType#PARAGRAPH}, les tableaux ressortent en {@link TableResult} (rendus en chunks
 * {@code TABLE} par le pipeline, comme les tableaux natifs).
 *
 * <p>Le {@link VisionBudget} est partagé avec l'extraction de tableaux : un seul cap par document.
 * Dégradation gracieuse : vision indisponible ou PDF illisible → résultat vide, jamais d'exception.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScannedDocumentFallbackService {

    /** Résolution de rendu d'une page entière pour la vision. */
    private static final float RENDER_DPI = 150f;
    /** Confiance d'un texte de page reconstruit par vision (alignée sur la base vision). */
    private static final double PAGE_TEXT_CONFIDENCE = 0.6;
    /** Confiance d'une page scannée non traitée (sur-budget / vision indisponible), à revoir manuellement. */
    private static final double MANUAL_REVIEW_CONFIDENCE = 0.3;

    private final VisionFallback visionFallback;
    private final ChunkingService chunkingService;

    /** Chunks de texte + tableaux issus du fallback vision des pages scannées. */
    public record ScannedExtraction(List<Chunk> textChunks, List<TableResult> tables) {
        public static ScannedExtraction empty() {
            return new ScannedExtraction(List.of(), List.of());
        }
    }

    public ScannedExtraction process(byte[] pdfBytes, ExtractedDocument doc,
                                     Set<Integer> scannedPages, VisionBudget budget) {
        if (doc == null || scannedPages == null || scannedPages.isEmpty()) {
            return ScannedExtraction.empty();
        }

        List<Chunk> textChunks = new ArrayList<>();
        List<TableResult> tables = new ArrayList<>();

        // Vision indisponible (désactivée / pas de clé) : aucune page ne peut être lue → toutes en revue.
        if (!visionFallback.isAvailable()) {
            for (int page : scannedPages) textChunks.add(manualReviewChunk(page));
            log.info("Scanned fallback — docId: {}, vision unavailable → {} page(s) flagged for manual review",
                    doc.documentId(), scannedPages.size());
            return new ScannedExtraction(textChunks, tables);
        }

        int pagesDone = 0, pagesFlagged = 0;
        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            for (int page : scannedPages) {
                VisionPageResult res = null;
                if (budget.hasRemaining()) {
                    try {
                        res = visionFallback.extractPage(renderFullPage(renderer, page), page);
                    } catch (Exception e) {
                        log.warn("Scanned fallback failed on page {} (docId {}): {}",
                                page, doc.documentId(), e.toString());
                    }
                }
                if (res != null) {
                    budget.tryConsume();
                    pagesDone++;
                    if (!res.text().isBlank()) {
                        textChunks.addAll(chunkVisionText(doc.documentId(), page, res.text()));
                    }
                    tables.addAll(res.tables());
                } else {
                    // Sur-budget, échec, ou page sans contenu exploitable → revue manuelle.
                    textChunks.add(manualReviewChunk(page));
                    pagesFlagged++;
                }
            }
        } catch (IOException e) {
            log.warn("Scanned fallback skipped - cannot load PDF (docId {})", doc.documentId(), e);
            // PDF illisible : aucune page traitée → tout passe en revue manuelle.
            for (int page : scannedPages) textChunks.add(manualReviewChunk(page));
            return new ScannedExtraction(textChunks, tables);
        }

        log.info("Scanned fallback — docId: {}, scanned: {}, vision: {}, manual review: {}, textChunks: {}, tables: {}",
                doc.documentId(), scannedPages.size(), pagesDone, pagesFlagged, textChunks.size(), tables.size());
        return new ScannedExtraction(textChunks, tables);
    }

    /**
     * Redécoupe le texte vision d'une page via {@link ChunkingService} (taille/overlap cohérents),
     * puis remappe chaque chunk en {@code fallbackUsed=true} (id provisoire {@code null}, ré-attribué
     * par le pipeline).
     */
    private List<Chunk> chunkVisionText(String docId, int page, String text) {
        ExtractedDocument tmp = new ExtractedDocument(docId, 1, "unknown", null,
                List.of(new ExtractedPage(page, text, false, false)));
        List<Chunk> sized = chunkingService.chunk(tmp);

        List<Chunk> out = new ArrayList<>(sized.size());
        for (Chunk c : sized) {
            out.add(new Chunk(null, c.text(), ChunkType.PARAGRAPH, page,
                    c.charStart(), c.charEnd(), PAGE_TEXT_CONFIDENCE, true, false, null));
        }
        return out;
    }

    /**
     * Placeholder pour une page scannée <strong>non traitée</strong> (cap vision atteint, vision
     * indisponible ou échec) : confiance basse et {@code manualReviewNeeded=true} pour signaler à
     * l'appelant qu'une relecture humaine est requise. Id provisoire {@code null} (ré-attribué).
     */
    private Chunk manualReviewChunk(int page) {
        String text = "[Scanned page %d - manual review needed]".formatted(page);
        return new Chunk(null, text, ChunkType.PARAGRAPH, page, 0, 0,
                MANUAL_REVIEW_CONFIDENCE, false, true, null);
    }

    /** Rend la page entière en PNG à {@link #RENDER_DPI}. */
    private byte[] renderFullPage(PDFRenderer renderer, int page) throws IOException {
        BufferedImage img = renderer.renderImageWithDPI(page - 1, RENDER_DPI);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
