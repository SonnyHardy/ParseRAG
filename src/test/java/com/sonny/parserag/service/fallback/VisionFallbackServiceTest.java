package com.sonny.parserag.service.fallback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.model.domain.TableResult;
import com.sonny.parserag.model.domain.VisionPageResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste la logique pure du fallback vision (parsing de la réponse JSON, disponibilité),
 * sans appel réseau.
 */
class VisionFallbackServiceTest {

    private final VisionFallbackService service =
            new VisionFallbackService(new AppProperties(), new ObjectMapper());

    @Test
    void parsesPlainJson() {
        String json = "{\"headers\":[\"A\",\"B\"],\"rows\":[[\"1\",\"2\"],[\"3\",\"4\"]]}";
        TableResult t = service.parseVisionContent(json, 5, "Table 1: Demo");

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
        TableResult t = service.parseVisionContent(json, 1, null);
        assertNotNull(t);
        assertEquals(List.of("X", "Y"), t.headers());
        assertEquals(1, t.rows().size());
    }

    @Test
    void returnsNullOnEmptyOrSingleColumn() {
        assertNull(service.parseVisionContent("{\"headers\":[],\"rows\":[]}", 1, null));
        assertNull(service.parseVisionContent("{\"headers\":[\"only\"],\"rows\":[[\"x\"]]}", 1, null));
        assertNull(service.parseVisionContent("", 1, null));
        assertNull(service.parseVisionContent("not json at all", 1, null));
    }

    @Test
    void unavailableWithoutApiKey() {
        AppProperties props = new AppProperties();
        props.getVision().setEnabled(true);   // activé mais clé vide en dev
        VisionFallbackService s = new VisionFallbackService(props, new ObjectMapper());
        assertFalse(s.isAvailable());
        assertNull(s.extractTable(new byte[]{1, 2, 3}, 1, null), "sans clé : aucun appel, null");
    }

    @Test
    void rectangularizesShortRowsToHeaderWidth() {
        // Le modèle omet parfois une cellule : la ligne courte doit être complétée par "".
        String json = "{\"headers\":[\"A\",\"B\",\"C\"],\"rows\":[[\"1\",\"2\",\"3\"],[\"4\",\"5\"]]}";
        TableResult t = service.parseVisionContent(json, 1, null);

        assertNotNull(t);
        assertEquals(3, t.colCount());
        assertTrue(t.rows().stream().allMatch(r -> r.size() == 3), "toutes les lignes alignées sur 3 colonnes");
        assertEquals(List.of("4", "5", ""), t.rows().get(1), "ligne courte complétée par \"\"");
    }

    @Test
    void padsHeaderWhenRowWiderThanHeaders() {
        // Inverse : une ligne plus large que l'en-tête → en-tête complété, aucune donnée tronquée.
        String json = "{\"headers\":[\"A\",\"B\"],\"rows\":[[\"1\",\"2\",\"3\"]]}";
        TableResult t = service.parseVisionContent(json, 1, null);

        assertNotNull(t);
        assertEquals(3, t.colCount());
        assertEquals(3, t.headers().size());
        assertEquals("", t.headers().get(2), "en-tête complété sans perte de données");
        assertEquals(List.of("1", "2", "3"), t.rows().get(0));
    }

    // ── Fallback plein-page (issue #11) : parsePageContent ──

    @Test
    void parsesPageWithTextAndTables() {
        String json = "{\"text\":\"Para un.\\nPara deux.\",\"tables\":["
                + "{\"headers\":[\"A\",\"B\"],\"rows\":[[\"1\",\"2\"]]}]}";
        VisionPageResult r = service.parsePageContent(json, 7);

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
        VisionPageResult r = service.parsePageContent("{\"text\":\"Body only.\",\"tables\":[]}", 1);
        assertNotNull(r);
        assertEquals("Body only.", r.text());
        assertTrue(r.tables().isEmpty());
    }

    @Test
    void parsesPageWithTablesOnly() {
        String json = "{\"text\":\"\",\"tables\":[{\"headers\":[\"X\",\"Y\"],\"rows\":[[\"a\",\"b\"]]}]}";
        VisionPageResult r = service.parsePageContent(json, 1);
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
        VisionPageResult r = service.parsePageContent(json, 1);
        assertNotNull(r);
        assertEquals(1, r.tables().size(), "la table à 1 colonne est écartée");
        assertEquals(List.of("1", "", ""), r.tables().getFirst().rows().getFirst(), "ligne complétée");
    }

    @Test
    void returnsNullOnEmptyOrUnusablePage() {
        assertNull(service.parsePageContent("{\"text\":\"\",\"tables\":[]}", 1), "ni texte ni table");
        assertNull(service.parsePageContent("", 1));
        assertNull(service.parsePageContent("not json", 1));
    }

    @Test
    void extractPageUnavailableWithoutApiKey() {
        AppProperties props = new AppProperties();
        props.getVision().setEnabled(true);   // activé mais clé vide
        VisionFallbackService s = new VisionFallbackService(props, new ObjectMapper());
        assertNull(s.extractPage(new byte[]{1, 2, 3}, 1), "sans clé : aucun appel, null");
    }
}
