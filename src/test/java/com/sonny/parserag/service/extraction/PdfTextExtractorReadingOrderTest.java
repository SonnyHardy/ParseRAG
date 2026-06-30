package com.sonny.parserag.service.extraction;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.model.domain.Chunk;
import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.model.domain.ExtractedPage;
import com.sonny.parserag.service.processing.ChunkingService;
import com.sonny.parserag.service.processing.ConfidenceCalculatorService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation du score d'ordre de lecture par page (issue #30, palier 2) sur le vrai BERT
 * (arxiv-1810.04805, 2 colonnes). Hors-ligne : extracteur pur, sans Spring ni datasource.
 * La page 1 (titre + abstract/intro dont les colonnes sont entrelacées à l'assemblage) doit
 * obtenir un score bas, là où les pages mono-colonne/colonnes correctement séparées restent hautes.
 */
class PdfTextExtractorReadingOrderTest {

    private static final AppProperties PROPS = props();
    private final PdfTextExtractorService extractor = new PdfTextExtractorService(PROPS, new PageGeometryAnalyzer());
    private final ChunkingService chunking = new ChunkingService(PROPS, new ConfidenceCalculatorService(PROPS));

    private static AppProperties props() {
        AppProperties props = new AppProperties();
        props.getExtraction().setStripLineNumbers(true);
        props.getConfidence().setMaxAnomalyRate(0.2);      // palier 1
        props.getConfidence().setPenaltyFloor(0.3);        // palier 1
        props.getConfidence().setMaxBackwardJumpRate(0.3); // palier 2
        props.getConfidence().setPageScoreFloor(0.3);      // palier 2
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
    void bert_page1IsInterleaved_othersAreClean() throws IOException {
        ExtractedDocument doc = extractor.extract(load("arxiv-1810.04805-bert-2col.pdf"), Plan.PRO);

        System.out.println("=== reading-order score par page (BERT) ===");
        for (ExtractedPage p : doc.pages()) {
            System.out.printf("  page %2d : %.2f%n", p.pageNumber(), p.readingOrderScore());
        }

        // Page 1 entrelacée (gouttière non détectée → mono → colonnes recollées) → score bas.
        assertTrue(doc.pages().get(0).readingOrderScore() < 0.6,
                "page 1 devrait être pénalisée (entrelacée)");
        // Pages texte pur à colonnes correctement séparées → score plein.
        for (int p : new int[]{2, 9, 11, 12}) {
            double s = doc.pages().get(p - 1).readingOrderScore();
            assertTrue(s >= 0.9, "page " + p + " (propre) devrait être haute, obtenu: " + s);
        }
    }

    @Test
    void bert_finalChunkConfidences_interleavedPageBelowCleanPage() throws IOException {
        // NB : sans nettoyage header/footer (hors-périmètre extracteur), donc on raisonne PAR PAGE,
        // pas par index de chunk — l'indexation diffère du pipeline complet.
        ExtractedDocument doc = extractor.extract(load("arxiv-1810.04805-bert-2col.pdf"), Plan.PRO);
        List<Chunk> chunks = chunking.chunk(doc);

        // Pire chunk d'une page entrelacée (p1) vs meilleur chunk d'une page propre (p9, références).
        double worstInterleaved = chunks.stream().filter(c -> c.page() == 1)
                .mapToDouble(Chunk::confidence).max().orElseThrow();
        double bestClean = chunks.stream().filter(c -> c.page() == 9)
                .mapToDouble(Chunk::confidence).max().orElseThrow();

        System.out.printf("page 1 (entrelacée) meilleur chunk = %.2f | page 9 (propre) meilleur chunk = %.2f%n",
                worstInterleaved, bestClean);
        assertTrue(worstInterleaved < bestClean,
                "même le meilleur chunk de la page entrelacée (" + worstInterleaved
                        + ") doit rester sous celui d'une page propre (" + bestClean + ")");
    }
}
