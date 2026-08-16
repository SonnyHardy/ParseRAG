package com.sonny.parserag.service.fallback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.model.domain.TableResult;
import com.sonny.parserag.model.domain.VisionPageResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste le fallback vision Gemini (issue #28) hors-ligne : l'appel au modèle est stubbé via le seam
 * {@link GeminiVisionFallbackService.GeminiCall}, aucun {@code Client} du SDK n'est construit.
 * On vérifie surtout le <strong>contrat de dégradation gracieuse</strong> du port
 * {@link VisionFallback} — c'est lui qui alimente {@code manual_review_needed} en aval.
 */
class GeminiVisionFallbackServiceTest {

    private static final byte[] PNG = {1, 2, 3};

    private static AppProperties props(String apiKey) {
        AppProperties p = new AppProperties();
        p.getVision().setEnabled(true);
        p.getGemini().setApiKey(apiKey);
        return p;
    }

    private static GeminiVisionFallbackService service(AppProperties p,
                                                       GeminiVisionFallbackService.GeminiCall call) {
        return new GeminiVisionFallbackService(p, new VisionResponseParser(new ObjectMapper()), call);
    }

    // ── Disponibilité ─────────────────────────────────────────────────────────────────────

    @Test
    void unavailableWithoutApiKey() {
        // Cas dev : GOOGLE_API_KEY vide. Aucun appel ne doit partir.
        AtomicInteger calls = new AtomicInteger();
        GeminiVisionFallbackService s = service(props(""),
                (sys, user, png, schema) -> {
                    calls.incrementAndGet();
                    return "{}";
                });

        assertFalse(s.isAvailable());
        assertNull(s.extractTable(PNG, 1, null), "sans clé : aucun appel, null");
        assertNull(s.extractPage(PNG, 1), "sans clé : aucun appel, null");
        assertEquals(0, calls.get(), "aucun appel au modèle sans clé");
    }

    @Test
    void unavailableWhenVisionDisabled() {
        AppProperties p = props("k");
        p.getVision().setEnabled(false);
        assertFalse(service(p, (sys, user, png, schema) -> "{}").isAvailable());
    }

    @Test
    void availableWithKeyAndVisionEnabled() {
        assertTrue(service(props("k"), (sys, user, png, schema) -> "{}").isAvailable());
    }

    // ── Chemin nominal ────────────────────────────────────────────────────────────────────

    @Test
    void extractsTableFromModelResponse() {
        GeminiVisionFallbackService s = service(props("k"), (sys, user, png, schema) -> {
            assertEquals(VisionPrompts.TABLE_SYSTEM, sys, "consigne système « tableau »");
            assertNotNull(schema, "la sortie doit être contrainte par un responseSchema");
            return "{\"headers\":[\"A\",\"B\"],\"rows\":[[\"1\",\"2\"]]}";
        });

        TableResult t = s.extractTable(PNG, 3, "Table 1");
        assertNotNull(t);
        assertEquals(3, t.page());
        assertEquals("Table 1", t.caption());
        assertEquals(List.of("A", "B"), t.headers());
        assertTrue(t.fallbackUsed());
    }

    @Test
    void extractsPageFromModelResponse() {
        GeminiVisionFallbackService s = service(props("k"), (sys, user, png, schema) -> {
            assertEquals(VisionPrompts.PAGE_SYSTEM, sys, "consigne système « page »");
            return "{\"text\":\"Corps de page.\",\"tables\":"
                    + "[{\"headers\":[\"X\",\"Y\"],\"rows\":[[\"a\",\"b\"]]}]}";
        });

        VisionPageResult r = s.extractPage(PNG, 4);
        assertNotNull(r);
        assertEquals(4, r.page());
        assertEquals("Corps de page.", r.text());
        assertEquals(1, r.tables().size());
    }

    // ── Dégradation gracieuse ─────────────────────────────────────────────────────────────

    @Test
    void returnsNullWhenModelCallThrows() {
        // Rate-limit épuisé, timeout, finishReason inattendu : jamais d'exception vers l'appelant.
        GeminiVisionFallbackService s = service(props("k"), (sys, user, png, schema) -> {
            throw new IllegalStateException("429 RESOURCE_EXHAUSTED");
        });

        assertNull(s.extractTable(PNG, 1, null));
        assertNull(s.extractPage(PNG, 1));
    }

    @Test
    void returnsNullOnEmptyOrUnusableResponse() {
        assertNull(service(props("k"), (sys, user, png, schema) -> null).extractPage(PNG, 1));
        assertNull(service(props("k"), (sys, user, png, schema) -> "pas du json").extractPage(PNG, 1));
        assertNull(service(props("k"), (sys, user, png, schema) -> "{\"text\":\"\",\"tables\":[]}")
                .extractPage(PNG, 1), "page sans rien d'exploitable");
    }

    @Test
    void skipsCallOnEmptyImage() {
        AtomicInteger calls = new AtomicInteger();
        GeminiVisionFallbackService s = service(props("k"), (sys, user, png, schema) -> {
            calls.incrementAndGet();
            return "{}";
        });

        assertNull(s.extractTable(null, 1, null));
        assertNull(s.extractTable(new byte[0], 1, null));
        assertNull(s.extractPage(null, 1));
        assertNull(s.extractPage(new byte[0], 1));
        assertEquals(0, calls.get(), "pas d'image → pas d'appel facturé");
    }
}
