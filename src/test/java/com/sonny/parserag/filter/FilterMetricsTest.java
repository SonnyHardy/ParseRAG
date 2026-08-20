package com.sonny.parserag.filter;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.observability.ParseRagMetrics;
import com.sonny.parserag.observability.TestMetrics;
import com.sonny.parserag.service.ratelimit.RateLimitService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vérifie que le rejet par la garde de débit est compté (issue #38). C'est le signal qui distingue
 * « l'API est en panne » de « quelque chose tape trop fort sur l'origine » — depuis l'issue #54, ce
 * n'est plus un compteur de facturation mais un compteur d'incident.
 */
class FilterMetricsTest {

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final ParseRagMetrics metrics = TestMetrics.metrics(meters);

    private RateLimitFilter filter(int freePerMinute) {
        AppProperties props = new AppProperties();
        AppProperties.RateLimit rl = props.getRateLimit();
        rl.setFreeRequestsPerMinute(freePerMinute);
        rl.setStarterRequestsPerMinute(30);
        rl.setProRequestsPerMinute(100);
        rl.setScaleRequestsPerMinute(300);

        RateLimitService service = new RateLimitService(props, metrics);
        return new RateLimitFilter(service, JsonMapper.builder().build(), metrics);
    }

    private static MockHttpServletRequest parseRequest() {
        return parseRequest("alice");
    }

    /** Le bucket etant par consommateur et par plan, la requete doit porter les deux. */
    private static MockHttpServletRequest parseRequest(String rapidApiUser) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/parse");
        request.setAttribute(RequestAttributes.RAPIDAPI_USER, rapidApiUser);
        request.setAttribute(RequestAttributes.PLAN, Plan.FREE);
        return request;
    }

    @Test
    void rateLimitRejectionIsCounted() throws Exception {
        RateLimitFilter filter = filter(1);   // un seul jeton : le 2ᵉ appel sera rejeté

        filter.doFilter(parseRequest(), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse rejected = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(parseRequest(), rejected, chain);

        assertEquals(429, rejected.getStatus());
        assertTrue(rejected.getContentAsString().contains("RATE_LIMIT_EXCEEDED"));
        assertEquals(1, meters.get(ParseRagMetrics.RATELIMIT_REJECTED).tag("plan", "free").counter().count());
    }

    @Test
    void anotherConsumerIsNotAffectedByTheFirstOnesRejection() throws Exception {
        RateLimitFilter filter = filter(1);
        filter.doFilter(parseRequest("alice"), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(parseRequest("alice"), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse bob = new MockHttpServletResponse();
        filter.doFilter(parseRequest("bob"), bob, new MockFilterChain());

        assertEquals(200, bob.getStatus(), "bob ne doit pas payer pour alice");
    }

    @Test
    void rejectionCarriesRetryAfter() throws Exception {
        RateLimitFilter filter = filter(1);
        filter.doFilter(parseRequest(), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(parseRequest(), rejected, new MockFilterChain());

        assertTrue(rejected.getHeader("Retry-After") != null,
                "un 429 sans Retry-After n'est pas actionnable par le client");
    }

    @Test
    void passingRequestCarriesNoRateLimitHeaders() throws Exception {
        // Les en-têtes X-RateLimit-* appartiennent désormais à RapidAPI : en publier d'autres,
        // portant un plafond d'infrastructure, induirait le client en erreur (issue #54).
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(100).doFilter(parseRequest(), response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaderNames().stream().noneMatch(h -> h.startsWith("X-RateLimit")));
    }
}
