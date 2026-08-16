package com.sonny.parserag.service.fallback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonny.parserag.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider historique (issue #28) : on ne teste plus que sa disponibilité — la traduction de la
 * réponse est couverte par {@link VisionResponseParserTest}, qu'il partage avec Gemini.
 */
class OpenAiVisionFallbackServiceTest {

    private static OpenAiVisionFallbackService service(AppProperties p) {
        return new OpenAiVisionFallbackService(p, new VisionResponseParser(new ObjectMapper()));
    }

    @Test
    void unavailableWithoutApiKey() {
        AppProperties props = new AppProperties();
        props.getVision().setEnabled(true);   // activé mais clé vide en dev

        OpenAiVisionFallbackService s = service(props);
        assertFalse(s.isAvailable());
        assertNull(s.extractTable(new byte[]{1, 2, 3}, 1, null), "sans clé : aucun appel, null");
        assertNull(s.extractPage(new byte[]{1, 2, 3}, 1), "sans clé : aucun appel, null");
    }

    @Test
    void availableWithKeyAndVisionEnabled() {
        AppProperties props = new AppProperties();
        props.getVision().setEnabled(true);
        props.getOpenai().setApiKey("sk-test");
        assertTrue(service(props).isAvailable());
    }
}
