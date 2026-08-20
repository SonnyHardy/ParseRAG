package com.sonny.parserag.filter;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.observability.TestMetrics;
import com.sonny.parserag.repository.ApiKeyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Exemption de la sonde de vivacité (issue #58).
 * <p>
 * C'est une brèche volontaire dans une chaîne dont l'invariant était « aucun chemin exempté »
 * (issue #37) : elle doit donc rester d'une largeur exactement égale à son motif — un orchestrateur
 * qui ne peut pas s'authentifier — et pas d'un octet de plus.
 */
class ProbePathsTest {

    private final ApiKeyRepository repository = mock(ApiKeyRepository.class);

    private ApiKeyFilter apiKeyFilter() {
        return new ApiKeyFilter(repository, JsonMapper.builder().build(),
                TestMetrics.metrics(new SimpleMeterRegistry()), new AppProperties());
    }

    private RapidApiProxyFilter proxyFilter() {
        AppProperties props = new AppProperties();
        props.getRapidapi().setProxySecret("secret-du-proxy");
        return new RapidApiProxyFilter(props, JsonMapper.builder().build(),
                TestMetrics.metrics(new SimpleMeterRegistry()));
    }

    private static MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    void theLivenessProbePassesWithoutAnyCredential() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        apiKeyFilter().doFilter(get(ProbePaths.LIVENESS), response, chain);

        assertNotNull(chain.getRequest(), "la sonde doit traverser sans clé");
        assertEquals(200, response.getStatus());
    }

    @Test
    void theLivenessProbeIsNotRateLimited() throws Exception {
        // Sans identité, la sonde partagerait le bucket « anonymous » : saturer ce bucket suffirait
        // à faire échouer la sonde, donc à faire redémarrer le conteneur. Le limiteur deviendrait
        // l'arme du déni de service.
        AppProperties props = new AppProperties();
        props.getRateLimit().setFreeRequestsPerMinute(1);
        props.getRateLimit().setStarterRequestsPerMinute(1);
        props.getRateLimit().setProRequestsPerMinute(1);
        props.getRateLimit().setScaleRequestsPerMinute(1);
        RateLimitFilter filter = new RateLimitFilter(
                new com.sonny.parserag.service.ratelimit.RateLimitService(
                        props, TestMetrics.metrics(new SimpleMeterRegistry())),
                JsonMapper.builder().build(), TestMetrics.metrics(new SimpleMeterRegistry()));

        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(get(ProbePaths.LIVENESS), response, new MockFilterChain());
            assertEquals(200, response.getStatus(), "sonde n°" + (i + 1));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/metrics",
            "/actuator/info",
            "/actuator/health/liveness",   // sous-chemin : l'exemption est une égalité, pas un préfixe
            "/actuator/healthz",
            "/api/v1/health",              // l'endpoint métier reste réservé à l'admin (issue #37)
            "/api/v1/parse"
    })
    void everyOtherPathStillNeedsAuthentication(String uri) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        apiKeyFilter().doFilter(get(uri), response, chain);

        assertNull(chain.getRequest(), uri + " ne doit pas traverser sans clé");
        assertEquals(401, response.getStatus());
    }

    @Test
    void theProxyFilterAlsoLetsTheProbeThrough() throws Exception {
        // Sinon la sonde recevrait 403 INVALID_PROXY_SECRET dès que l'intégration est configurée,
        // c'est-à-dire exactement en production.
        MockFilterChain chain = new MockFilterChain();

        proxyFilter().doFilter(get(ProbePaths.LIVENESS), new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
    }
}
