package com.sonny.parserag.model.response;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verrouille le contrat de sortie de GET /api/v1/health (issue #37).
 * <p>
 * Le mapper est construit nu, sans la configuration Spring Boot : si la sérialisation reste correcte
 * ici, c'est que le format ne dépend d'aucun réglage global (en particulier
 * {@code WRITE_DATES_AS_TIMESTAMPS}, qui rendrait le timestamp sous forme d'epoch numérique).
 */
class HealthResponseTest {

    @Test
    void serializesToTheDocumentedShape() {
        HealthResponse health = new HealthResponse(
                "UP", "UP", "UP", "UP",
                Instant.parse("2026-07-11T15:30:00Z"), "ParseRAG", "0.0.1-SNAPSHOT");

        String json = JsonMapper.builder().build().writeValueAsString(health);

        assertEquals("""
                {"status":"UP","db":"UP","flyway":"UP","disk":"UP","timestamp":"2026-07-11T15:30:00Z",\
                "application":"ParseRAG","version":"0.0.1-SNAPSHOT"}""", json);
    }

    /** Le composant fautif reste identifiable dans le corps du 503 — c'est tout l'intérêt du détail. */
    @Test
    void serializesEachComponentIndependently() {
        HealthResponse health = new HealthResponse(
                "DOWN", "UP", "UP", "DOWN",
                Instant.parse("2026-07-11T15:30:00Z"), "ParseRAG", "0.0.1-SNAPSHOT");

        String json = JsonMapper.builder().build().writeValueAsString(health);

        assertEquals("""
                {"status":"DOWN","db":"UP","flyway":"UP","disk":"DOWN","timestamp":"2026-07-11T15:30:00Z",\
                "application":"ParseRAG","version":"0.0.1-SNAPSHOT"}""", json);
    }
}
