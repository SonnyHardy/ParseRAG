package com.sonny.parserag.service.fallback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonny.parserag.model.domain.TableResult;
import com.sonny.parserag.model.domain.VisionPageResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste la traduction JSON → domaine, commune aux deux fournisseurs de vision (issue #28).
 * Logique pure : aucun appel réseau.
 */
class VisionResponseParserTest {

    private final VisionResponseParser parser = new VisionResponseParser(new ObjectMapper());

    @Test
    void parsesPlainJson() {
        String json = "{\"headers\":[\"A\",\"B\"],\"rows\":[[\"1\",\"2\"],[\"3\",\"4\"]]}";
        TableResult t = parser.parseTable(json, 5, "Table 1: Demo");

        assertNotNull(t);
        assertEquals(5, t.page());
        assertEquals("Table 1: Demo", t.caption());
        assertEquals(List.of("A", "B"), t.headers());
        assertEquals(2, t.colCount());
        assertEquals(3, t.rowCount());
        assertEquals(2, t.rows().size());
        assertTrue(t.fallbackUsed(), "un tableau vision doit porter fallbackUsed=true");
    }

    @Test
    void toleratesMarkdownCodeFences() {
        String json = "```json\n{\"headers\":[\"X\",\"Y\"],\"rows\":[[\"a\",\"b\"]]}\n```";
        TableResult t = parser.parseTable(json, 1, null);
        assertNotNull(t);
        assertEquals(List.of("X", "Y"), t.headers());
        assertEquals(1, t.rows().size());
    }

    @Test
    void returnsNullOnEmptyOrSingleColumn() {
        assertNull(parser.parseTable("{\"headers\":[],\"rows\":[]}", 1, null));
        assertNull(parser.parseTable("{\"headers\":[\"only\"],\"rows\":[[\"x\"]]}", 1, null));
        assertNull(parser.parseTable("", 1, null));
        assertNull(parser.parseTable("not json at all", 1, null));
    }

    @Test
    void rectangularizesShortRowsToHeaderWidth() {
        // Le modèle omet parfois une cellule : la ligne courte doit être complétée par "".
        String json = "{\"headers\":[\"A\",\"B\",\"C\"],\"rows\":[[\"1\",\"2\",\"3\"],[\"4\",\"5\"]]}";
        TableResult t = parser.parseTable(json, 1, null);

        assertNotNull(t);
        assertEquals(3, t.colCount());
        assertTrue(t.rows().stream().allMatch(r -> r.size() == 3), "toutes les lignes alignées sur 3 colonnes");
        assertEquals(List.of("4", "5", ""), t.rows().get(1), "ligne courte complétée par \"\"");
    }

    @Test
    void padsHeaderWhenRowWiderThanHeaders() {
        // Inverse : une ligne plus large que l'en-tête → en-tête complété, aucune donnée tronquée.
        String json = "{\"headers\":[\"A\",\"B\"],\"rows\":[[\"1\",\"2\",\"3\"]]}";
        TableResult t = parser.parseTable(json, 1, null);

        assertNotNull(t);
        assertEquals(3, t.colCount());
        assertEquals(3, t.headers().size());
        assertEquals("", t.headers().get(2), "en-tête complété sans perte de données");
        assertEquals(List.of("1", "2", "3"), t.rows().get(0));
    }

    // ── Fallback plein-page (issue #11) : parsePage ──

    @Test
    void parsesPageWithTextAndTables() {
        String json = "{\"text\":\"Para un.\\nPara deux.\",\"tables\":["
                + "{\"headers\":[\"A\",\"B\"],\"rows\":[[\"1\",\"2\"]]}]}";
        VisionPageResult r = parser.parsePage(json, 7);

        assertNotNull(r);
        assertEquals(7, r.page());
        assertEquals("Para un.\nPara deux.", r.text());
        assertEquals(1, r.tables().size());
        TableResult t = r.tables().getFirst();
        assertEquals(7, t.page(), "le tableau porte la page de la réponse");
        assertEquals(List.of("A", "B"), t.headers());
        assertTrue(t.fallbackUsed());
    }

    @Test
    void parsesPageWithTextOnly() {
        VisionPageResult r = parser.parsePage("{\"text\":\"Body only.\",\"tables\":[]}", 1);
        assertNotNull(r);
        assertEquals("Body only.", r.text());
        assertTrue(r.tables().isEmpty());
    }

    @Test
    void parsesPageWithTablesOnly() {
        String json = "{\"text\":\"\",\"tables\":[{\"headers\":[\"X\",\"Y\"],\"rows\":[[\"a\",\"b\"]]}]}";
        VisionPageResult r = parser.parsePage(json, 1);
        assertNotNull(r);
        assertEquals("", r.text());
        assertEquals(1, r.tables().size());
    }

    @Test
    void pageRectangularizesAndSkipsInvalidTables() {
        // 1ʳᵉ table valide mais ragged (à compléter) ; 2ᵉ table dégénérée (1 colonne) → ignorée.
        String json = "{\"text\":\"t\",\"tables\":["
                + "{\"headers\":[\"A\",\"B\",\"C\"],\"rows\":[[\"1\"]]},"
                + "{\"headers\":[\"solo\"],\"rows\":[[\"x\"]]}]}";
        VisionPageResult r = parser.parsePage(json, 1);
        assertNotNull(r);
        assertEquals(1, r.tables().size(), "la table à 1 colonne est écartée");
        assertEquals(List.of("1", "", ""), r.tables().getFirst().rows().getFirst(), "ligne complétée");
    }

    @Test
    void returnsNullOnEmptyOrUnusablePage() {
        assertNull(parser.parsePage("{\"text\":\"\",\"tables\":[]}", 1), "ni texte ni table");
        assertNull(parser.parsePage("", 1));
        assertNull(parser.parsePage("not json", 1));
    }
}
