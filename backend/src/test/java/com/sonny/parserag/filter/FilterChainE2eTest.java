package com.sonny.parserag.filter;

import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.model.response.ParseResponse;
import com.sonny.parserag.repository.ApiKeyRepository;
import com.sonny.parserag.service.pipeline.ParsePipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chaîne de filtres <strong>assemblée</strong>, place de marché active (issue #59).
 *
 * <p>Chaque filtre était déjà éprouvé isolément ; ce que ces tests couvrent est ce qu'aucun test
 * unitaire ne peut voir — l'<em>ordonnancement</em>, dont dépendent les garanties de sécurité :
 * {@code RapidApiProxyFilter} authentifie le trafic public et pose le plan, {@code ApiKeyFilter} ne
 * réclame une clé interne que si le proxy n'a rien posé, et le limiteur de débit ferme la marche.
 * Inverser deux maillons, ou oublier un {@code FilterRegistrationBean(setEnabled(false))}, ne casse
 * aucun test unitaire.
 *
 * <p><strong>Aucune base n'est requise</strong> : les auto-configurations JDBC/JPA/Flyway sont
 * exclues et les deux seuls collaborateurs qui en dépendent sont doublés. Le pipeline lui-même est
 * doublé aussi — l'objet du test est la chaîne, pas le parsing, et un vrai parse rendrait ces
 * assertions lentes et bruyantes.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration,"
                + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        // Secret renseigné = place de marché en service : c'est le régime de production.
        "parserag.rapidapi.proxy-secret=secret-du-proxy",
        "parserag.rapidapi.plan-mapping.BASIC=FREE",
        "parserag.rapidapi.plan-mapping.MEGA=SCALE",
        // Un seul jeton : suffit à prouver qu'un filtre ne s'exécute pas deux fois (voir plus bas).
        "parserag.rate-limit.free-requests-per-minute=1",
        "parserag.rate-limit.starter-requests-per-minute=1",
        "parserag.rate-limit.scale-requests-per-minute=1"
})
@AutoConfigureMockMvc
class FilterChainE2eTest {

    private static final String PARSE = "/api/v1/parse";
    private static final String KEY = "cle-interne";
    private static final String KEY_HASH =
            "0e2e2f2f2a8f4a5e5b0e0e2e2f2f2a8f4a5e5b0e0e2e2f2f2a8f4a5e5b0e0e2e";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ApiKeyRepository apiKeyRepository;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ParsePipelineService pipeline;

    @BeforeEach
    void stubPipeline() {
        when(pipeline.process(any(), any()))
                .thenReturn(ParseResponse.ok("doc_test", 1, "en", 12, List.of()));
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "doc.pdf", "application/pdf", "%PDF-1.4".getBytes());
    }

    /**
     * Identifiant distinct par clé : le bucket de debit est indexe sur l'identite de l'appelant, et
     * des cles sans id partageraient toutes le bucket « anonymous » — un test consommerait alors le
     * jeton d'un autre, et l'echec dependrait de l'ordre d'execution.
     */
    private static ApiKey key(boolean admin) {
        ApiKey k = new ApiKey();
        k.setId(UUID.randomUUID());
        k.setKeyHash(KEY_HASH);
        k.setPlan(Plan.STARTER);
        k.setAdmin(admin);
        k.setActive(true);
        return k;
    }

    // ── Absence d'authentification ────────────────────────────────────────────────────────

    @Test
    void withoutAnyCredentialTheRequestIsRefusedInTheStandardErrorShape() throws Exception {
        mvc.perform(multipart(PARSE).file(pdf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("MISSING_API_KEY"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.status").value(401));

        verify(pipeline, never()).process(any(), any());
    }

    // ── Chemin place de marché ────────────────────────────────────────────────────────────

    @Test
    void aValidProxySecretAuthenticatesAndItsPlanReachesThePipeline() throws Exception {
        mvc.perform(multipart(PARSE).file(pdf())
                        .header("X-RapidAPI-Proxy-Secret", "secret-du-proxy")
                        .header("X-RapidAPI-Subscription", "MEGA")
                        .header("X-RapidAPI-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        // L'assertion qui compte : le plan annoncé par le proxy est bien celui appliqué en aval,
        // et aucune lecture de clé n'a eu lieu — le chemin public ne touche pas la base.
        verify(pipeline).process(any(), eq(Plan.SCALE));
        verify(apiKeyRepository, never()).findByKeyHashAndActiveTrue(anyString());
    }

    @Test
    void aWrongProxySecretIsRefusedWithoutFallingBackToTheInternalKey() throws Exception {
        when(apiKeyRepository.findByKeyHashAndActiveTrue(anyString())).thenReturn(Optional.of(key(true)));

        mvc.perform(multipart(PARSE).file(pdf())
                        .header("X-RapidAPI-Proxy-Secret", "mauvais-secret")
                        .header("X-API-Key", KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INVALID_PROXY_SECRET"));

        // Même muni d'une clé interne valide : un mauvais secret n'a pas droit à une seconde chance.
        verify(pipeline, never()).process(any(), any());
    }

    @Test
    void aForgedSubscriptionHeaderGrantsNoPlanWithoutTheSecret() throws Exception {
        // Le scénario d'escalade : appel direct, en-tête de plan fabriqué, clé interne admin.
        when(apiKeyRepository.findByKeyHashAndActiveTrue(anyString())).thenReturn(Optional.of(key(true)));

        mvc.perform(multipart(PARSE).file(pdf())
                        .header("X-API-Key", KEY)
                        .header("X-RapidAPI-Subscription", "MEGA"))
                .andExpect(status().isOk());

        // Le plan vient de la clé (STARTER), jamais de l'en-tête forgé (SCALE).
        verify(pipeline).process(any(), eq(Plan.STARTER));
    }

    // ── Chemin interne, refermé sur l'administration (issue #56) ──────────────────────────

    @Test
    void aNonAdminInternalKeyIsRefusedWhileTheMarketplaceServesTraffic() throws Exception {
        when(apiKeyRepository.findByKeyHashAndActiveTrue(anyString())).thenReturn(Optional.of(key(false)));

        mvc.perform(multipart(PARSE).file(pdf()).header("X-API-Key", KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("MARKETPLACE_REQUIRED"))
                .andExpect(jsonPath("$.status").value(403));

        verify(pipeline, never()).process(any(), any());
    }

    // ── Sonde de vivacité (issue #58) ─────────────────────────────────────────────────────

    @Test
    void theLivenessProbeCrossesTheWholeChainUnauthenticated() throws Exception {
        mvc.perform(get(ProbePaths.LIVENESS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void otherActuatorEndpointsRemainAuthenticated() throws Exception {
        mvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
    }

    // ── Débit ─────────────────────────────────────────────────────────────────────────────

    @Test
    void exceedingTheRateLimitYieldsA429CarryingRetryAfter() throws Exception {
        when(apiKeyRepository.findByKeyHashAndActiveTrue(anyString())).thenReturn(Optional.of(key(true)));

        // Le plan de la clé est STARTER, mais le budget configuré ici est d'un jeton : la première
        // requête passe — ce qui prouve au passage que le filtre n'a consommé qu'un seul jeton,
        // donc qu'il ne s'est pas exécuté deux fois faute de FilterRegistrationBean désactivé.
        mvc.perform(multipart(PARSE).file(pdf()).header("X-API-Key", KEY))
                .andExpect(status().isOk());

        mvc.perform(multipart(PARSE).file(pdf()).header("X-API-Key", KEY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(header().exists("Retry-After"));
    }
}
