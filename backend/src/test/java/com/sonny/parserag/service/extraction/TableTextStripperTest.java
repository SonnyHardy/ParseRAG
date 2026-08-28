package com.sonny.parserag.service.extraction;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.model.domain.TableRegion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vérifie que les lignes couvertes par une région de tableau sont retirées du {@code rawText}
 * (anti-duplication texte/tableau), hors-ligne sur table1.pdf.
 */
class TableTextStripperTest {

    private final PageGeometryAnalyzer geometry = new PageGeometryAnalyzer();
    private final TableRegionDetector detector = new TableRegionDetector(geometry);
    private final TableTextStripper stripper = new TableTextStripper(geometry);
    private final PdfTextExtractorService extractor = new PdfTextExtractorService(appProps(), geometry);

    private static AppProperties appProps() {
        AppProperties p = new AppProperties();
        p.getPageLimits().setMaxPagesFree(100);
        return p;
    }

    private byte[] load(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/sample-pdfs/pdfs/" + name)) {
            assertNotNull(in, "PDF introuvable : " + name);
            return in.readAllBytes();
        }
    }

    @Test
    void removesTableLinesFromRawText() throws IOException {
        byte[] pdf = load("table1.pdf");
        ExtractedDocument doc = extractor.extract(pdf, Plan.FREE);

        String before = doc.pages().getFirst().rawText();
        assertTrue(before.contains("Blind"), "le contenu du tableau doit d'abord être présent dans le texte");

        List<TableRegion> regions = detector.detect(pdf);
        ExtractedDocument stripped = stripper.strip(pdf, doc, regions);

        String after = stripped.pages().getFirst().rawText();
        assertFalse(after.contains("Blind"), "les lignes du tableau doivent être retirées du texte");
        assertFalse(after.contains("Mobility"), "les lignes du tableau doivent être retirées du texte");
    }
}
