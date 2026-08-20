package com.sonny.parserag.filter;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.observability.ParseRagMetrics;
import com.sonny.parserag.observability.TestMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Garanties du filtre RapidAPI (issue #54). Trois d'entre elles sont des invariants de sécurité,
 * pas des détails de comportement :
 * <ul>
 *   <li>un secret faux est refusé, et ne retombe pas sur l'authentification interne ;</li>
 *   <li>un en-tête d'abonnement <em>sans</em> secret valide n'accorde aucun plan ;</li>
 *   <li>un abonnement inconnu retombe sur le plan le plus restrictif.</li>
 * </ul>
 */
class RapidApiProxyFilterTest {

    private static final String SECRET = "s3cr3t-du-proxy";

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final ParseRagMetrics metrics = TestMetrics.metrics(meters);

    private AppProperties props(String current, String previous) {
        AppProperties p = new AppProperties();
        p.getRapidapi().setProxySecret(current);
        p.getRapidapi().setPreviousProxySecret(previous);
        p.getRapidapi().setPlanMapping(Map.of(
                "BASIC", Plan.FREE, "PRO", Plan.STARTER, "ULTRA", Plan.PRO, "MEGA", Plan.SCALE));
        return p;
    }

    private RapidApiProxyFilter filter(AppProperties props) {
        return new RapidApiProxyFilter(props, JsonMapper.builder().build(), metrics);
    }

    private static MockHttpServletRequest request(String secret, String subscription) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/parse");
        if (secret != null) r.addHeader(RapidApiProxyFilter.SECRET_HEADER, secret);
        if (subscription != null) r.addHeader(RapidApiProxyFilter.SUBSCRIPTION_HEADER, subscription);
        return r;
    }

    // ── Secret ────────────────────────────────────────────────────────────────────────────

    @Test
    void validSecretAuthenticatesAndResolvesThePlan() throws Exception {
        MockHttpServletRequest request = request(SECRET, "ULTRA");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(props(SECRET, "")).doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "la requête doit poursuivre la chaîne");
        assertEquals(Plan.PRO, request.getAttribute(RequestAttributes.PLAN));
        assertEquals(200, response.getStatus());
    }

    @Test
    void wrongSecretIsRejectedAndDoesNotFallBackToTheInternalKey() throws Exception {
        MockHttpServletRequest request = request("mauvais-secret", "MEGA");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(props(SECRET, "")).doFilter(request, response, chain);

        assertNull(chain.getRequest(), "la chaîne ne doit pas être poursuivie");
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("INVALID_PROXY_SECRET"));
        // Qui présente un mauvais secret n'a pas droit à une seconde chance par la clé interne.
        assertNull(request.getAttribute(RequestAttributes.PLAN));
        assertEquals(1, counter("invalid_proxy_secret"));
    }

    @Test
    void missingSecretHeaderFallsThroughToTheInternalKeyPath() throws Exception {
        MockHttpServletRequest request = request(null, null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(props(SECRET, "")).doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "sans en-tête, c'est ApiKeyFilter qui tranche");
        assertNull(request.getAttribute(RequestAttributes.PLAN));
        assertEquals(200, response.getStatus());
    }

    @Test
    void secretPresentedWhileIntegrationIsUnconfiguredIsRejected() throws Exception {
        // Un déploiement qui oublie RAPIDAPI_PROXY_SECRET ne doit pas devenir ouvert à tous.
        MockHttpServletRequest request = request("n-importe-quoi", "MEGA");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(props("", "")).doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void previousSecretIsStillAcceptedDuringRotation() throws Exception {
        MockHttpServletRequest request = request("ancien-secret", "BASIC");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(props(SECRET, "ancien-secret")).doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(Plan.FREE, request.getAttribute(RequestAttributes.PLAN));
    }

    // ── Plan ──────────────────────────────────────────────────────────────────────────────

    @Test
    void unknownSubscriptionFallsBackToTheMostRestrictivePlan() throws Exception {
        MockHttpServletRequest request = request(SECRET, "PLAN-QUI-N-EXISTE-PAS");
        filter(props(SECRET, "")).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(Plan.FREE, request.getAttribute(RequestAttributes.PLAN));
    }

    @Test
    void missingSubscriptionHeaderFallsBackToTheMostRestrictivePlan() throws Exception {
        MockHttpServletRequest request = request(SECRET, null);
        filter(props(SECRET, "")).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(Plan.FREE, request.getAttribute(RequestAttributes.PLAN));
    }

    @Test
    void subscriptionMatchingIsCaseInsensitive() throws Exception {
        // Une différence de casse ne doit pas rétrograder silencieusement un client payant.
        MockHttpServletRequest request = request(SECRET, "mega");
        filter(props(SECRET, "")).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(Plan.SCALE, request.getAttribute(RequestAttributes.PLAN));
    }

    @Test
    void forgedSubscriptionHeaderGrantsNothingWithoutAValidSecret() throws Exception {
        // Le scénario d'escalade : appel direct sur l'origine, en-tête de plan fabriqué.
        MockHttpServletRequest request = request(null, "MEGA");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(props(SECRET, "")).doFilter(request, response, chain);

        assertNull(request.getAttribute(RequestAttributes.PLAN),
                "aucun plan ne doit être accordé sans validation du secret");
    }

    private double counter(String reason) {
        return meters.get(ParseRagMetrics.AUTH_FAILURES).tag("reason", reason).counter().count();
    }
}
