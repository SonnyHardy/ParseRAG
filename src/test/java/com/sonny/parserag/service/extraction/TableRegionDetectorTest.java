package com.sonny.parserag.service.extraction;

import com.sonny.parserag.model.domain.TableRegion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test isolé (sans contexte Spring, donc sans Postgres) du {@link TableRegionDetector}
 * sur trois PDF représentatifs : un papier scientifique sans bordures (BERT, 8 tableaux)
 * et deux documents Word à tableaux quadrillés (table1, tables_examples).
 *
 * <p>Logue chaque région détectée pour inspection visuelle des rectangles.
 */
class TableRegionDetectorTest {

    private final TableRegionDetector detector = new TableRegionDetector(new PageGeometryAnalyzer());

    private byte[] load(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/sample-pdfs/pdfs/" + name)) {
            assertNotNull(in, "PDF introuvable dans les ressources de test : " + name);
            return in.readAllBytes();
        }
    }

    private void dump(String label, List<TableRegion> regions) {
        System.out.printf("%n=== %s : %d région(s) ===%n", label, regions.size());
        for (TableRegion r : regions) {
            System.out.printf("  p%-2d %s [%4.0f,%4.0f → %4.0f,%4.0f] col=%d lines=%d  caption=%s%n",
                    r.page(), r.bordered() ? "BORDERED  " : "borderless",
                    r.x0(), r.y0(), r.x1(), r.y1(), r.columnIndex(), r.rowLineCount(), r.caption());
        }
    }

    @Test
    void bert_eightBorderlessTablesAnchored() throws IOException {
        List<TableRegion> regions = detector.detect(load("arxiv-1810.04805-bert-2col.pdf"));
        dump("BERT", regions);

        // Les 8 légendes Table 1..8 doivent être ancrées.
        Pattern num = Pattern.compile("(?i)\\btable\\s*(\\d+)");
        boolean[] seen = new boolean[9];
        for (TableRegion r : regions) {
            if (r.caption() == null) continue;
            Matcher m = num.matcher(r.caption());
            if (m.find()) {
                int n = Integer.parseInt(m.group(1));
                if (n >= 1 && n <= 8) seen[n] = true;
            }
        }
        for (int n = 1; n <= 8; n++) assertTrue(seen[n], "Table " + n + " non détecté");
    }

    @Test
    void table1_oneBorderedTable() throws IOException {
        List<TableRegion> regions = detector.detect(load("table1.pdf"));
        dump("table1", regions);
        assertTrue(regions.stream().anyMatch(TableRegion::bordered),
                "aucune région à bordures détectée");
    }

    @Test
    void tablesExamples_fourBorderedTablesNoGraphs() throws IOException {
        List<TableRegion> regions = detector.detect(load("tables_examples.pdf"));
        dump("tables_examples", regions);
        long bordered = regions.stream().filter(TableRegion::bordered).count();
        assertEquals(4, bordered, "attendu 4 tableaux à bordures (2/page), graphiques exclus");
    }
}
