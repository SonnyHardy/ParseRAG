package com.sonny.parserag.service.extraction;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.model.domain.ExtractedPage;
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
import java.io.InputStream;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Valide la détection de page scannée sur de <em>vrais</em> PDF, via {@link PdfTextExtractorService} :
 * <ul>
 *   <li>un PDF <strong>image-only</strong> synthétisé en mémoire (texte rastérisé, aucun texte natif)
 *       → page détectée comme scannée ;</li>
 *   <li>un PDF <strong>natif</strong> du corpus ({@code table1.pdf}) → aucune page scannée (anti-faux-positif).</li>
 * </ul>
 */
class ScannedPageDetectorPdfTest {

    private final ScannedPageDetector detector = new ScannedPageDetector();

    private static PdfTextExtractorService extractor() {
        AppProperties props = new AppProperties();
        props.getPageLimits().setMaxPagesFree(100);   // sinon cap à 0 page
        return new PdfTextExtractorService(props, new PageGeometryAnalyzer());
    }

    /** Construit en mémoire un PDF 1 page « scanné » : une image plein-cadre, sans aucun texte natif. */
    private static byte[] imageOnlyPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            BufferedImage img = new BufferedImage(620, 877, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.setColor(Color.BLACK);
            g.drawString("This text is drawn as pixels, not as a PDF text operator.", 40, 80);
            g.dispose();

            PDImageXObject pdImage = LosslessFactory.createFromImage(doc, img);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(pdImage, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void imageOnlyPdf_pageDetectedAsScanned() throws Exception {
        ExtractedDocument doc = extractor().extract(imageOnlyPdf(), Plan.FREE);

        assertEquals(1, doc.pageCount());
        ExtractedPage p = doc.pages().getFirst();
        assertTrue(p.hasImages(), "la page porte une image");
        assertTrue(p.rawText() == null || p.rawText().isBlank(), "aucun texte natif");
        assertEquals(Set.of(1), detector.scannedPages(doc), "page image-only → scannée");
    }

    @Test
    void nativePdf_isNotDetectedAsScanned() throws Exception {
        byte[] bytes;
        try (InputStream in = getClass().getResourceAsStream("/sample-pdfs/pdfs/table1.pdf")) {
            assertNotNull(in, "fixture table1.pdf manquante sur le classpath de test");
            bytes = in.readAllBytes();
        }
        ExtractedDocument doc = extractor().extract(bytes, Plan.FREE);
        assertTrue(detector.scannedPages(doc).isEmpty(), "un PDF natif ne doit pas être vu comme scanné");
    }
}
