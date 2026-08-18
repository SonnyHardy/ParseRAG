package com.sonny.parserag.service.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.model.domain.TableResult;
import com.sonny.parserag.observability.TestMetrics;
import com.sonny.parserag.service.fallback.GeminiVisionFallbackService;
import com.sonny.parserag.service.fallback.VisionResponseParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste la logique pure de normalisation + filtrage qualité ({@code buildFromGrid}),
 * sans dépendre de Tabula.
 */
class TableExtractorServiceTest {

    private final TableExtractorService service = new TableExtractorService(
            new AppProperties(),
            new TableRegionDetector(new PageGeometryAnalyzer()),
            new GeminiVisionFallbackService(new AppProperties(), new VisionResponseParser(new ObjectMapper()),
                    TestMetrics.metrics()),
            TestMetrics.metrics());
    private final AppProperties.Tables cfg = defaultCfg();

    private static AppProperties.Tables defaultCfg() {
        AppProperties.Tables c = new AppProperties.Tables();
        c.setEnabled(true);
        c.setBorderlessFallback(true);
        c.setMinRows(2);
        c.setMinCols(2);
        c.setMinFillRatio(0.5);
        return c;
    }

    private static List<List<String>> grid(String[]... rows) {
        List<List<String>> g = new ArrayList<>();
        for (String[] r : rows) g.add(new ArrayList<>(Arrays.asList(r)));
        return g;
    }

    @Test
    void acceptanceCriterion_3cols_5rows_allCellsNonEmpty() {
        List<List<String>> g = grid(
                new String[]{"Produit", "Q1", "Q2"},
                new String[]{"Widget A", "120", "145"},
                new String[]{"Widget B", "80", "92"},
                new String[]{"Widget C", "60", "75"},
                new String[]{"Widget D", "40", "55"});

        TableResult t = service.buildFromGrid(g, 5, null, false, cfg);

        assertNotNull(t);
        assertEquals(5, t.rowCount());
        assertEquals(3, t.colCount());
        assertEquals(List.of("Produit", "Q1", "Q2"), t.headers());
        assertEquals(4, t.rows().size());
        boolean allNonEmpty = t.rows().stream().flatMap(List::stream).noneMatch(String::isEmpty);
        assertTrue(allNonEmpty);
    }

    @Test
    void dropsFullyEmptyRowsAndColumns() {
        List<List<String>> g = grid(
                new String[]{"A", "", "B"},
                new String[]{"", "", ""},      // ligne vide → retirée
                new String[]{"C", "", "D"});   // colonne du milieu entièrement vide → retirée
        TableResult t = service.buildFromGrid(g, 1, null, false, cfg);
        assertNotNull(t);
        assertEquals(2, t.colCount());     // colonne vide retirée
        assertEquals(2, t.rowCount());     // ligne vide retirée
    }

    @Test
    void rejectsTooSmall() {
        assertNull(service.buildFromGrid(grid(new String[]{"a", "b"}), 1, null, false, cfg)); // 1 ligne
        assertNull(service.buildFromGrid(
                grid(new String[]{"a"}, new String[]{"b"}), 1, null, false, cfg));            // 1 colonne
    }

    @Test
    void rejectsSparseGrid() {
        // 3x3, aucune ligne/colonne entièrement vide (donc rien n'est retiré),
        // mais 4/9 ≈ 0.44 cellules remplies → sous le seuil 0.5.
        List<List<String>> g = grid(
                new String[]{"x", "", "y"},
                new String[]{"", "z", ""},
                new String[]{"", "", "w"});
        assertNull(service.buildFromGrid(g, 1, null, false, cfg));
    }

    @Test
    void borderlessHasLowerConfidenceThanBordered() {
        List<List<String>> g = grid(
                new String[]{"H1", "H2"},
                new String[]{"a", "b"},
                new String[]{"c", "d"});
        double bordered = service.buildFromGrid(g, 1, null, false, cfg).confidence();
        double borderless = service.buildFromGrid(g, 1, null, true, cfg).confidence();
        assertTrue(borderless < bordered, "sans bordures doit être moins fiable");
    }

    @Test
    void rejectsReferenceList() {
        List<List<String>> g = grid(
                new String[]{"[1]", "Cam-Winget N et al. (2019) Using XMPP for Security Exchange. RFC 8600."},
                new String[]{"[2]", "Software Defined Perimeter Working Group. SDP Specification 1.0. 2014."},
                new String[]{"[3]", "Stanton B et al. (2016) Security Fatigue. IT Professional 18(5)."});
        assertNull(service.buildFromGrid(g, 59, null, false, cfg));
    }

    @Test
    void captionIsCarried() {
        List<List<String>> g = grid(new String[]{"H1", "H2"}, new String[]{"a", "b"});
        TableResult t = service.buildFromGrid(g, 3, "Table 1: Ventes", false, cfg);
        assertEquals("Table 1: Ventes", t.caption());
    }

    @Test
    void semanticQuality_cleanTableScoresHigh_proseContaminatedScoresLow() {
        // Table propre : cellules brèves, aucune ligne singleton.
        TableResult clean = service.buildFromGrid(grid(
                new String[]{"Tasks", "MNLI", "QNLI"},
                new String[]{"No NSP", "81.5", "84.9"},
                new String[]{"LTR", "77.5", "84.3"},
                new String[]{"BiLSTM", "82.1", "84.1"}), 1, null, true, cfg);

        // Grille polluée par de la prose : phrases longues happées, lignes singleton.
        TableResult prose = service.buildFromGrid(grid(
                new String[]{"Note that the purpose of the masking is to reduce", "", ""},
                new String[]{"MASK", "SAME", "RND"},
                new String[]{"the mismatch between pre-training and fine-tuning", "", ""},
                new String[]{"80%", "10%", "10%"}), 1, null, true, cfg);

        double cleanQ = service.semanticQuality(clean);
        double proseQ = service.semanticQuality(prose);

        assertTrue(cleanQ >= 0.9, "table propre attendue ≥ 0.9, obtenu " + cleanQ);
        assertTrue(proseQ < 0.75, "table polluée attendue < seuil 0.75, obtenu " + proseQ);
        assertTrue(cleanQ > proseQ);
    }

    // ── hasStructuralDefect : signaux complétant semanticQuality pour le routage vision ──

    private static TableResult table(List<String> headers, List<List<String>> rows) {
        return new TableResult(1, null, headers, rows, rows.size() + 1, headers.size(), 0.6, false);
    }

    @Test
    void structuralDefect_hollowColumn() {
        // Colonne B remplie seulement à l'en-tête (1/5 = 0.2) → colonne fantôme d'un mauvais découpage.
        TableResult t = table(
                List.of("A", "B", "C", "D"),
                List.of(List.of("a1", "", "c1", "d1"),
                        List.of("a2", "", "c2", "d2"),
                        List.of("a3", "", "c3", "d3"),
                        List.of("a4", "", "c4", "d4")));
        assertTrue(service.hasStructuralDefect(t), "colonne creuse → défaut");
    }

    @Test
    void structuralDefect_numericHeaderWithBlankFirstCell() {
        // En-tête happé (tailles à la place des noms de colonnes) + 1ʳᵉ cellule vide.
        TableResult t = table(
                List.of("", "392k", "363k", "108k"),
                List.of(List.of("Pre-OpenAI", "80.6", "66.1", "82.3"),
                        List.of("BERTBASE", "84.6", "71.2", "90.5")));
        assertTrue(service.hasStructuralDefect(t), "en-tête numérique + 1ʳᵉ cellule vide → défaut");
    }

    @Test
    void structuralDefect_numericHeaderButLabeledFirstCell_isNotDefect() {
        // En-têtes légitimement numériques (années) mais 1ʳᵉ colonne étiquetée → le durcissement
        // « 1ʳᵉ cellule vide » empêche le faux positif.
        TableResult t = table(
                List.of("Year", "2018", "2019", "2020"),
                List.of(List.of("Sales", "100", "110", "120"),
                        List.of("Cost", "60", "65", "70")));
        assertFalse(service.hasStructuralDefect(t),
                "1ʳᵉ cellule étiquetée → numeric_header ne doit pas déclencher");
    }

    @Test
    void structuralDefect_replacementChar() {
        TableResult t = table(
                List.of("A", "B"),
                List.of(List.of("112�112", "ok"),
                        List.of("x", "y")));
        assertTrue(service.hasStructuralDefect(t), "glyphe non décodé → défaut");
    }

    @Test
    void structuralDefect_cleanTable_isNotDefect() {
        TableResult t = table(
                List.of("Produit", "Q1", "Q2"),
                List.of(List.of("Widget A", "120", "145"),
                        List.of("Widget B", "80", "92")));
        assertFalse(service.hasStructuralDefect(t), "table propre → aucun défaut");
    }
}
