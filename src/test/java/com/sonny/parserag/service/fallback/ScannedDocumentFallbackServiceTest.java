package com.sonny.parserag.service.fallback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.model.domain.Chunk;
import com.sonny.parserag.model.domain.ChunkType;
import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.model.domain.TableResult;
import com.sonny.parserag.model.domain.VisionPageResult;
import com.sonny.parserag.service.fallback.ScannedDocumentFallbackService.ScannedExtraction;
import com.sonny.parserag.service.processing.ChunkingService;
import com.sonny.parserag.service.processing.ConfidenceCalculatorService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste le mapping du fallback vision plein-page → chunks, sur un PDF image-only synthétisé,
 * avec une {@link VisionFallbackService} factice (aucun appel réseau).
 */
class ScannedDocumentFallbackServiceTest {

    private static AppProperties props() {
        AppProperties p = new AppProperties();
        p.getChunking().setMaxChunkSize(2000);
        p.getChunking().setOverlap(100);
        p.getChunking().setMinChunkSize(30);
        p.getVision().setEnabled(true);
        return p;
    }

    /** Vision factice : disponibilité paramétrable, renvoie texte + un tableau pour toute page. */
    private static VisionFallbackService stubVision(AppProperties p, boolean available) {
        return new VisionFallbackService(p, new ObjectMapper()) {
            @Override
            public boolean isAvailable() {
                return available;
            }

            @Override
            public VisionPageResult extractPage(byte[] png, int page) {
                return new VisionPageResult(page,
                        "Reconstructed readable body text for scanned page " + page + ".",
                        List.of(new TableResult(page, null, List.of("A", "B"),
                                List.of(List.of("1", "2")), 2, 2, 0.6, true)));
            }
        };
    }

    private static ScannedDocumentFallbackService service(AppProperties p, boolean available) {
        return new ScannedDocumentFallbackService(stubVision(p, available),
                new ChunkingService(p, new ConfidenceCalculatorService(p)));
    }

    private static ExtractedDocument doc(String id, int pages) {
        return new ExtractedDocument(id, pages, "en", null, List.of());
    }

    /** PDF image-only de {@code n} pages (chaque page = une image plein-cadre, sans texte natif). */
    private static byte[] imageOnlyPdf(int n) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < n; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                BufferedImage img = new BufferedImage(400, 560, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, img.getWidth(), img.getHeight());
                g.dispose();
                PDImageXObject pdImage = LosslessFactory.createFromImage(doc, img);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(pdImage, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void mapsVisionResultToChunksAndTables() throws Exception {
        AppProperties p = props();
        VisionBudget budget = new VisionBudget(20);

        ScannedExtraction res = service(p, true)
                .process(imageOnlyPdf(2), doc("d1", 2), Set.of(1, 2), budget);

        assertEquals(2, res.textChunks().size(), "un chunk texte par page (texte court)");
        assertTrue(res.textChunks().stream().allMatch(Chunk::fallbackUsed), "fallback_used=true");
        assertTrue(res.textChunks().stream().allMatch(c -> c.type() == ChunkType.PARAGRAPH));
        assertEquals(2, res.tables().size(), "un tableau par page");
        assertEquals(2, budget.used(), "deux pages → deux appels vision consommés");
    }

    @Test
    void overBudgetPageFlaggedForManualReview() throws Exception {
        AppProperties p = props();
        VisionBudget budget = new VisionBudget(1);   // une seule page autorisée

        ScannedExtraction res = service(p, true)
                .process(imageOnlyPdf(2), doc("d1", 2), Set.of(1, 2), budget);

        assertEquals(1, budget.used(), "le cap partagé borne le nombre d'appels vision");
        assertEquals(1, res.tables().size(), "seule la page vision produit un tableau");

        long vision = res.textChunks().stream().filter(Chunk::fallbackUsed).count();
        long review = res.textChunks().stream().filter(Chunk::manualReviewNeeded).count();
        assertEquals(1, vision, "1 page traitée par vision");
        assertEquals(1, review, "1 page sur-budget → revue manuelle");
        assertTrue(res.textChunks().stream().filter(Chunk::manualReviewNeeded)
                .allMatch(c -> c.confidence() == 0.3 && !c.fallbackUsed()));
    }

    @Test
    void visionUnavailableFlagsAllPagesForReview() throws Exception {
        ScannedExtraction res = service(props(), false)
                .process(imageOnlyPdf(2), doc("d1", 2), Set.of(1, 2), new VisionBudget(20));

        assertEquals(2, res.textChunks().size(), "chaque page scannée → un placeholder de revue");
        assertTrue(res.textChunks().stream().allMatch(Chunk::manualReviewNeeded));
        assertTrue(res.textChunks().stream().allMatch(c -> c.confidence() == 0.3 && !c.fallbackUsed()));
        assertTrue(res.tables().isEmpty());
    }

    @Test
    void emptyWhenNoScannedPages() throws Exception {
        ScannedExtraction res = service(props(), true)
                .process(imageOnlyPdf(1), doc("d1", 1), Set.of(), new VisionBudget(20));
        assertTrue(res.textChunks().isEmpty());
        assertTrue(res.tables().isEmpty());
    }
}
