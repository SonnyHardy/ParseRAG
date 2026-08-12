package com.sonny.parserag.filter;

import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.repository.ApiKeyRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests de {@link ApiKeyFilter} : résolution de la clé, refus 401/403 au format d'erreur standard,
 * et surtout — depuis l'issue #37 — le fait que {@code /api/v1/health} ne soit <strong>plus</strong>
 * exempté d'authentification.
 * <p>
 * Le filtre est exercé via {@code doFilter} (et non {@code doFilterInternal}) afin que l'éventuel
 * {@code shouldNotFilter} soit réellement pris en compte.
 */
class ApiKeyFilterTest {

    /**
     * Clé arbitraire et son SHA-256 : le test vérifie que le filtre hashe bien la valeur du header
     * avant lookup. Volontairement indépendant de la clé seedée en V1.
     */
    private static final String RAW_KEY = "test-key-dev-123";
    private static final String RAW_KEY_HASH =
            "c66e219a7174453210477d9e9f9eb0dda0fbdafa69196f79390b95418ee18fd8";

    private final ApiKeyRepository repository = mock(ApiKeyRepository.class);
    private final ApiKeyFilter filter = new ApiKeyFilter(repository, JsonMapper.builder().build());

    private static ApiKey adminKey() {
        ApiKey key = new ApiKey();
        key.setKeyHash(RAW_KEY_HASH);
        key.setPlan(Plan.FREE);
        key.setAdmin(true);
        return key;
    }

    private record Outcome(MockHttpServletResponse response, MockFilterChain chain) {
        boolean chainWasInvoked() {
            return chain.getRequest() != null;
        }
    }

    private Outcome call(String uri, String rawKey, MockHttpServletRequest request) throws Exception {
        request.setRequestURI(uri);
        if (rawKey != null) {
            request.addHeader("X-API-Key", rawKey);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);
        return new Outcome(response, chain);
    }

    private Outcome call(String uri, String rawKey) throws Exception {
        return call(uri, rawKey, new MockHttpServletRequest("GET", uri));
    }

    // ── /health n'est plus public (issue #37) ────────────────────────────────

    @Test
    void health_withoutKey_isUnauthorized() throws Exception {
        Outcome outcome = call("/api/v1/health", null);

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, outcome.response().getStatus());
        assertTrue(outcome.response().getContentAsString().contains("MISSING_API_KEY"));
        assertTrue(outcome.response().getContentAsString().contains("\"status\":401"));
        assertFalse(outcome.chainWasInvoked(), "la requête ne doit pas atteindre le contrôleur");
    }

    @Test
    void health_withUnknownKey_isForbidden() throws Exception {
        when(repository.findByKeyHashAndActiveTrue(anyString())).thenReturn(Optional.empty());

        Outcome outcome = call("/api/v1/health", "not-a-real-key");

        assertEquals(HttpServletResponse.SC_FORBIDDEN, outcome.response().getStatus());
        assertTrue(outcome.response().getContentAsString().contains("INVALID_API_KEY"));
        assertFalse(outcome.chainWasInvoked());
    }

    @Test
    void health_withValidKey_passesKeyDownstream() throws Exception {
        ApiKey key = adminKey();
        when(repository.findByKeyHashAndActiveTrue(RAW_KEY_HASH)).thenReturn(Optional.of(key));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        Outcome outcome = call("/api/v1/health", RAW_KEY, request);

        assertTrue(outcome.chainWasInvoked(), "la requête doit atteindre le contrôleur");
        assertSame(key, request.getAttribute("apiKey"));
    }

    // ── Base injoignable ─────────────────────────────────────────────────────

    /**
     * Le lookup de clé est une lecture en base : si Postgres est down, elle lève. Sans le catch du
     * filtre, l'exception remonterait hors du DispatcherServlet et le client recevrait la page
     * /error par défaut en 500 — y compris sur /health, dont le 503 soigné ne serait jamais atteint
     * puisque le contrôleur n'est pas appelé.
     */
    @Test
    void databaseDown_isServiceUnavailableInStandardErrorShape() throws Exception {
        when(repository.findByKeyHashAndActiveTrue(anyString()))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        Outcome outcome = call("/api/v1/health", RAW_KEY);

        assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, outcome.response().getStatus());
        assertEquals(MediaType.APPLICATION_JSON_VALUE, outcome.response().getContentType());
        assertTrue(outcome.response().getContentAsString().contains("DATABASE_UNAVAILABLE"));
        assertTrue(outcome.response().getContentAsString().contains("\"status\":503"));
        assertFalse(outcome.chainWasInvoked(), "la requête ne doit pas atteindre le contrôleur");
    }

    /**
     * Cas le plus fréquent en réalité : le repository Spring Data est {@code @Transactional},
     * l'échec survient donc à l'ouverture de la transaction — une {@link TransactionException},
     * qui n'est <strong>pas</strong> une {@code DataAccessException}. Ce test verrouille le fait
     * que les deux familles sont attrapées.
     */
    @Test
    void databaseDownAtTransactionStart_isServiceUnavailable() throws Exception {
        when(repository.findByKeyHashAndActiveTrue(anyString()))
                .thenThrow(new CannotCreateTransactionException("could not open JPA EntityManager"));

        Outcome outcome = call("/api/v1/parse", RAW_KEY);

        assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, outcome.response().getStatus());
        assertTrue(outcome.response().getContentAsString().contains("DATABASE_UNAVAILABLE"));
        assertFalse(outcome.chainWasInvoked());
    }

    // ── Comportement inchangé sur les autres endpoints ───────────────────────

    @Test
    void parse_withoutKey_isUnauthorized() throws Exception {
        Outcome outcome = call("/api/v1/parse", null);

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, outcome.response().getStatus());
        assertEquals(MediaType.APPLICATION_JSON_VALUE, outcome.response().getContentType());
    }

    @Test
    void parse_withValidKey_passesKeyDownstream() throws Exception {
        ApiKey key = adminKey();
        when(repository.findByKeyHashAndActiveTrue(RAW_KEY_HASH)).thenReturn(Optional.of(key));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/parse");
        Outcome outcome = call("/api/v1/parse", RAW_KEY, request);

        assertTrue(outcome.chainWasInvoked());
        assertSame(key, request.getAttribute("apiKey"));
    }

    /** Une clé blanche est traitée comme absente : 401, pas de lookup en base. */
    @Test
    void blankKey_isUnauthorized() throws Exception {
        Outcome outcome = call("/api/v1/health", "   ");

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, outcome.response().getStatus());
    }
}
