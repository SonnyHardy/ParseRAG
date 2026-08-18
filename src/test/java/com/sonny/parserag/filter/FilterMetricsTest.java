package com.sonny.parserag.filter;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.model.response.UsageResponse;
import com.sonny.parserag.observability.ParseRagMetrics;
import com.sonny.parserag.observability.TestMetrics;
import com.sonny.parserag.service.ratelimit.RateLimitService;
import com.sonny.parserag.service.usage.UsageTrackingService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Vérifie que les rejets de débit et de quota sont comptés, taggés par plan (issue #38). Ce sont
 * les deux signaux qui distinguent « l'API est en panne » de « le client tape trop fort », donc
 * ceux qu'on ne veut pas voir se perdre.
 */
class FilterMetricsTest {

    private static final String PARSE_URI = "/api/v1/parse";

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final ParseRagMetrics metrics = TestMetrics.metrics(meters);

    private static ApiKey key(Plan plan) {
        ApiKey k = new ApiKey();
        k.setId(UUID.randomUUID());
        k.setPlan(plan);
        return k;
    }

    private static MockHttpServletRequest parseRequest(ApiKey apiKey) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PARSE_URI);
        request.setRequestURI(PARSE_URI);
        request.setAttribute("apiKey", apiKey);
        return request;
    }

    // ── Rate limit ────────────────────────────────────────────────────────────────────────

    @Test
    void rateLimitRejectionIsCountedByPlan() throws Exception {
        AppProperties props = new AppProperties();
        AppProperties.RateLimit rl = props.getRateLimit();
        rl.setFreeRequestsPerMinute(1);       // un seul jeton : le 2ᵉ appel sera rejeté
        rl.setStarterRequestsPerMinute(30);
        rl.setProRequestsPerMinute(100);
        rl.setScaleRequestsPerMinute(300);

        RateLimitFilter filter = new RateLimitFilter(
                new RateLimitService(props, TestMetrics.metrics()),
                JsonMapper.builder().build(), metrics);

        ApiKey apiKey = key(Plan.FREE);
        filter.doFilter(parseRequest(apiKey), new MockHttpServletResponse(), new MockFilterChain());
        assertTrue(meters.find(ParseRagMetrics.RATELIMIT_REJECTED).counters().isEmpty(),
                "une requête qui passe ne doit rien compter");

        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(parseRequest(apiKey), rejected, new MockFilterChain());

        assertEquals(429, rejected.getStatus());
        assertEquals(1, meters.get(ParseRagMetrics.RATELIMIT_REJECTED)
                .tag("plan", "free").counter().count());
    }

    // ── Quota ─────────────────────────────────────────────────────────────────────────────

    @Test
    void quotaRejectionIsCountedByPlanAndRatioRecorded() throws Exception {
        UsageTrackingService usage = mock(UsageTrackingService.class);
        when(usage.currentUsage(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new UsageResponse("starter", 1000, 1000, 0, "2026-09-01", "2026-08"));

        QuotaEnforcementFilter filter =
                new QuotaEnforcementFilter(usage, JsonMapper.builder().build(), metrics);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(parseRequest(key(Plan.STARTER)), response, new MockFilterChain());

        assertEquals(429, response.getStatus());
        assertEquals(1, meters.get(ParseRagMetrics.QUOTA_REJECTED)
                .tag("plan", "starter").counter().count());
        assertEquals(1.0, meters.get(ParseRagMetrics.QUOTA_USAGE_RATIO)
                .tag("plan", "starter").summary().totalAmount(), 1e-9);
    }

    @Test
    void quotaRatioIsRecordedEvenWhenTheRequestPasses() throws Exception {
        // Le ratio sert à voir venir la saturation : ne le relever qu'au moment du rejet
        // reviendrait à n'avoir la donnée qu'une fois le client déjà bloqué.
        UsageTrackingService usage = mock(UsageTrackingService.class);
        when(usage.currentUsage(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new UsageResponse("pro", 1000, 5000, 4000, "2026-09-01", "2026-08"));

        QuotaEnforcementFilter filter =
                new QuotaEnforcementFilter(usage, JsonMapper.builder().build(), metrics);

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(parseRequest(key(Plan.PRO)), new MockHttpServletResponse(), chain);

        assertEquals(0.2, meters.get(ParseRagMetrics.QUOTA_USAGE_RATIO)
                .tag("plan", "pro").summary().totalAmount(), 1e-9);
        assertTrue(meters.find(ParseRagMetrics.QUOTA_REJECTED).counters().isEmpty());
    }

    @Test
    void nonParseRequestIsNotMeasured() throws Exception {
        // Le filtre quota ne s'applique qu'au parse : /usage ne doit ni être compté ni lire l'usage.
        UsageTrackingService usage = mock(UsageTrackingService.class);
        QuotaEnforcementFilter filter =
                new QuotaEnforcementFilter(usage, JsonMapper.builder().build(), metrics);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/usage");
        request.setRequestURI("/api/v1/usage");
        request.setAttribute("apiKey", key(Plan.FREE));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertTrue(meters.find(ParseRagMetrics.QUOTA_USAGE_RATIO).summaries().isEmpty());
        assertTrue(meters.find(ParseRagMetrics.QUOTA_REJECTED).counters().isEmpty());
    }
}
