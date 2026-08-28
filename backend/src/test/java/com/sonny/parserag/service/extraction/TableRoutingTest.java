package com.sonny.parserag.service.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.model.domain.TableResult;
import com.sonny.parserag.observability.TestMetrics;
import com.sonny.parserag.service.fallback.GeminiVisionFallbackService;
import com.sonny.parserag.service.fallback.VisionResponseParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test du routage d'extraction (détection région → Tabula ciblé / fallback vision), hors-ligne.
 * La vision est indisponible (clé d'API vide), donc seuls les chemins Tabula ciblés sont exercés.
 * Logue chaque tableau extrait pour inspection.
 */
class TableRoutingTest {

    private final TableExtractorService service = buildService();

    private static TableExtractorService buildService() {
        AppProperties props = new AppProperties();
        props.getTables().setEnabled(true);
        props.getTables().setMinRows(2);
        props.getTables().setMinCols(2);
        props.getTables().setMinFillRatio(0.4);
        props.getVision().setEnabled(true);   // activé mais clé vide → isAvailable() == false
        props.getVision().setQualityThreshold(0.75);
        return new TableExtractorService(
                props,
                new TableRegionDetector(new PageGeometryAnalyzer()),
                new GeminiVisionFallbackService(props, new VisionResponseParser(new ObjectMapper()),
                        TestMetrics.metrics()),
                TestMetrics.metrics());
    }

    private byte[] load(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/sample-pdfs/pdfs/" + name)) {
            assertNotNull(in, "PDF introuvable : " + name);
            return in.readAllBytes();
        }
    }

    private ExtractedDocument doc(int pages) {
        return new ExtractedDocument("doc_test", pages, "en", null, List.of());
    }

    private void dump(String label, List<TableResult> tables) {
        System.out.printf("%n=== %s : %d tableau(x) ===%n", label, tables.size());
        for (TableResult t : tables) {
            System.out.printf("  p%-2d %dx%d conf=%.2f fallback=%s caption=%s%n",
                    t.page(), t.rowCount(), t.colCount(), t.confidence(), t.fallbackUsed(), t.caption());
            System.out.printf("       headers=%s%n", t.headers());
        }
    }

    @Test
    void table1_borderedExtractedByTabula() throws IOException {
        List<TableResult> tables = service.extract(load("table1.pdf"), doc(1));
        dump("table1", tables);
        assertFalse(tables.isEmpty(), "le tableau à bordures doit être extrait");
        TableResult t = tables.getFirst();
        assertFalse(t.fallbackUsed(), "pas de vision sur un tableau à bordures");
        assertTrue(t.colCount() >= 2 && t.rowCount() >= 2);
    }

    @Test
    void tablesExamples_fourBorderedTables() throws IOException {
        List<TableResult> tables = service.extract(load("tables_examples.pdf"), doc(2));
        dump("tables_examples", tables);
        assertTrue(tables.size() >= 4, "attendu au moins 4 tableaux à bordures");
        assertTrue(tables.stream().noneMatch(TableResult::fallbackUsed));
    }

    @Test
    void bert_borderlessNoVisionWithoutKey() throws IOException {
        List<TableResult> tables = service.extract(load("arxiv-1810.04805-bert-2col.pdf"), doc(16));
        dump("BERT", tables);
        // Vision indisponible (pas de clé) → aucun tableau ne doit porter le flag fallback.
        assertTrue(tables.stream().noneMatch(TableResult::fallbackUsed),
                "sans clé OpenAI, aucun fallback vision ne doit être marqué");
    }
}
