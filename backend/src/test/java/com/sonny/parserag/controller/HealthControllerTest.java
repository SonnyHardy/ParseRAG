package com.sonny.parserag.controller;

import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.exception.ParseRagException;
import com.sonny.parserag.model.response.HealthResponse;
import com.sonny.parserag.service.health.HealthService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests de {@link HealthController} (issue #37) : restriction aux clés admin et traduction de
 * l'état des composants en code HTTP.
 * <p>
 * Le cas « 401 sans clé » n'est pas testable ici : il est traité en amont par {@code ApiKeyFilter},
 * qui ne laisse jamais une requête sans clé valide atteindre le contrôleur (cf. ApiKeyFilterTest).
 */
class HealthControllerTest {

    private static ApiKey key(boolean admin) {
        ApiKey k = new ApiKey();
        k.setPlan(Plan.FREE);
        k.setAdmin(admin);
        return k;
    }

    /** Requête telle que la voit le contrôleur : l'attribut apiKey est posé par ApiKeyFilter. */
    private static HttpServletRequest requestWith(ApiKey apiKey) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        if (apiKey != null) {
            request.setAttribute("apiKey", apiKey);
        }
        return request;
    }

    /** Service rendant le même état sur tous les composants. */
    private static HealthService serviceReporting(String state) {
        return serviceReporting(state, state, state, state);
    }

    private static HealthService serviceReporting(String status, String db, String flyway, String disk) {
        HealthService service = mock(HealthService.class);
        when(service.check()).thenReturn(new HealthResponse(
                status, db, flyway, disk,
                Instant.parse("2026-07-11T15:30:00Z"), "ParseRAG", "0.0.1-SNAPSHOT"));
        return service;
    }

    // ── Autorisation ─────────────────────────────────────────────────────────

    /**
     * Une clé valide mais non-admin reçoit un 404, pas un 403 : le 403 confirmerait l'existence
     * d'un endpoint d'administration à qui possède déjà une clé légitime.
     */
    @Test
    void nonAdminKey_isNotFound() {
        HealthService service = serviceReporting("UP");

        ParseRagException ex = assertThrows(ParseRagException.class,
                () -> new HealthController(service).health(requestWith(key(false))));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("NOT_FOUND", ex.getErrorCode());
        // Aucune fuite d'information : le health n'est même pas calculé pour un non-admin.
        verify(service, never()).check();
    }

    /** Garde défensive : sans attribut apiKey (filtre absent/mal ordonné), on refuse aussi. */
    @Test
    void missingApiKeyAttribute_isNotFound() {
        ParseRagException ex = assertThrows(ParseRagException.class,
                () -> new HealthController(serviceReporting("UP")).health(requestWith(null)));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("NOT_FOUND", ex.getErrorCode());
    }

    // ── Statut ───────────────────────────────────────────────────────────────

    @Test
    void adminKeyAndEverythingUp_returns200() {
        ResponseEntity<HealthResponse> response =
                new HealthController(serviceReporting("UP")).health(requestWith(key(true)));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().status());
        assertEquals("UP", response.getBody().db());
        assertEquals("UP", response.getBody().flyway());
        assertEquals("UP", response.getBody().disk());
        assertEquals("ParseRAG", response.getBody().application());
        assertEquals("0.0.1-SNAPSHOT", response.getBody().version());
    }

    @Test
    void adminKeyAndDatabaseDown_returns503WithBody() {
        ResponseEntity<HealthResponse> response =
                new HealthController(serviceReporting("DOWN")).health(requestWith(key(true)));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody(), "le 503 doit porter le corps de statut, pas un corps vide");
        assertEquals("DOWN", response.getBody().status());
        assertEquals("DOWN", response.getBody().db());
    }

    /**
     * Le 503 ne dépend pas de la seule base : un composant secondaire ({@code disk} ici) suffit,
     * et le corps doit permettre d'identifier lequel.
     */
    @Test
    void adminKeyAndOnlyDiskDown_returns503AndNamesTheFaultyComponent() {
        ResponseEntity<HealthResponse> response = new HealthController(
                serviceReporting("DOWN", "UP", "UP", "DOWN")).health(requestWith(key(true)));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().db());
        assertEquals("UP", response.getBody().flyway());
        assertEquals("DOWN", response.getBody().disk());
    }
}
