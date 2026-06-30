package com.sonny.parserag.service.extraction;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.model.domain.Chunk;
import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.service.processing.ChunkingService;
import com.sonny.parserag.service.processing.ConfidenceCalculatorService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation du score d'ordre de lecture par chunk (issue #30, palier 3) sur le vrai BERT
 * (arxiv-1810.04805, 2 colonnes). Hors-ligne : extracteur + chunking purs, sans Spring ni datasource.
 * La page 1 (colonnes entrelacées à l'assemblage) émet des lignes suspectes attribuées aux chunks ;
 * les pages à colonnes correctement séparées n'en émettent aucune (chunks non pénalisés).
 */
class PdfTextExtractorReadingOrderTest {

    private static final AppProperties PROPS = props();
    private final PdfTextExtractorService extractor = new PdfTextExtractorService(PROPS, new PageGeometryAnalyzer());
    private final ChunkingService chunking = new ChunkingService(PROPS, new ConfidenceCalculatorService(PROPS));

    private static AppProperties props() {
        AppProperties props = new AppProperties();
        props.getExtraction().setStripLineNumbers(true);
        props.getConfidence().setMaxAnomalyRate(0.2);        // palier 1
        props.getConfidence().setPenaltyFloor(0.3);          // palier 1
        props.getConfidence().setMaxSuspectLineRate(0.3);    // palier 3
        props.getConfidence().setReadingOrderFloor(0.3);     // palier 3
        props.getConfidence().setManualReviewThreshold(0.4); // palier 3
        props.getChunking().setMaxChunkSize(2000);
        props.getChunking().setOverlap(100);
        props.getChunking().setMinChunkSize(30);
        props.getPageLimits().setMaxPagesFree(100);
        props.getPageLimits().setMaxPagesStarter(100);
        props.getPageLimits().setMaxPagesPro(100);
        props.getPageLimits().setMaxPagesScale(100);
        return props;
    }

    private byte[] load(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/sample-pdfs/pdfs/" + name)) {
            assertNotNull(in, "PDF introuvable : " + name);
            return in.readAllBytes();
        }
    }

    @Test
    void bert_interleavedPageEmitsSuspectLines_cleanPagesDoNot() throws IOException {
        ExtractedDocument doc = extractor.extract(load("arxiv-1810.04805-bert-2col.pdf"), Plan.PRO);

        System.out.println("=== lignes suspectes d'ordre de lecture par page (BERT) ===");
        doc.pages().forEach(p ->
                System.out.printf("  page %2d : %d lignes suspectes%n", p.pageNumber(), p.reorderSuspectLines().size()));

        // Page 1 entrelacée (gouttière non détectée → mono) → lignes suspectes émises.
        assertFalse(doc.pages().get(0).reorderSuspectLines().isEmpty(),
                "page 1 devrait émettre des lignes suspectes");
        // Pages texte pur à colonnes correctement séparées → aucune ligne suspecte.
        for (int p : new int[]{2, 9, 11, 12}) {
            assertTrue(doc.pages().get(p - 1).reorderSuspectLines().isEmpty(),
                    "page " + p + " (propre) ne devrait émettre aucune ligne suspecte");
        }
    }

    @Test
    void bert_perChunkConfidence_interleavedBelowClean_andManualReviewFlagged() throws IOException {
        ExtractedDocument doc = extractor.extract(load("arxiv-1810.04805-bert-2col.pdf"), Plan.PRO);
        List<Chunk> chunks = chunking.chunk(doc);

        System.out.println("=== chunks page 1 (entrelacée) : confidence + manual_review ===");
        chunks.stream().filter(c -> c.page() == 1).forEach(c ->
                System.out.printf("  %s conf=%.2f review=%s  %s%n", c.id(), c.confidence(),
                        c.manualReviewNeeded(), c.text().substring(0, Math.min(45, c.text().length())).replace("\n", " ")));

        // Pivot par page : meilleur chunk d'une page entrelacée (p1) < meilleur d'une page propre (p9).
        double bestInterleaved = chunks.stream().filter(c -> c.page() == 1)
                .mapToDouble(Chunk::confidence).max().orElseThrow();
        double bestClean = chunks.stream().filter(c -> c.page() == 9)
                .mapToDouble(Chunk::confidence).max().orElseThrow();
        assertTrue(bestInterleaved < bestClean,
                "page 1 entrelacée (" + bestInterleaved + ") doit rester sous page 9 propre (" + bestClean + ")");

        // manual_review_needed est câblé sur la confidence finale (seuil 0.4).
        double threshold = PROPS.getConfidence().getManualReviewThreshold();
        assertTrue(chunks.stream().allMatch(c -> c.manualReviewNeeded() == (c.confidence() < threshold)),
                "manual_review doit refléter exactement confidence < seuil");
        // Les chunks entrelacés (page 1) sont marqués...
        assertTrue(chunks.stream().filter(c -> c.page() == 1).anyMatch(Chunk::manualReviewNeeded),
                "au moins un chunk de la page entrelacée devrait être marqué manual_review");
        // ...et le meilleur chunk d'une page propre (lecture saine, confidence haute) ne l'est pas.
        Chunk bestCleanChunk = chunks.stream().filter(c -> c.page() == 9)
                .max((a, b) -> Double.compare(a.confidence(), b.confidence())).orElseThrow();
        assertFalse(bestCleanChunk.manualReviewNeeded(),
                "le meilleur chunk d'une page propre ne devrait pas être marqué manual_review");
    }
}
