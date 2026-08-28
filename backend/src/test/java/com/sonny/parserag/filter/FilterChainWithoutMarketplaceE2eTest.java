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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chaîne de filtres assemblée, <strong>place de marché inactive</strong> (issue #59) — le régime du
 * développement local et de la période précédant la publication du listing.
 *
 * <p>Deux garanties ne s'observent que dans ce régime : le {@code 404} volontaire de
 * {@code /api/v1/health} pour une clé non-admin (issue #37, où un {@code 403} confirmerait
 * l'existence de l'endpoint), et le fait qu'une clé interne ordinaire continue de servir — la
 * fermeture décidée en #56 ne doit pas déborder sur le développement.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration,"
                + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        // Aucun secret : l'intégration n'est pas configurée.
        "parserag.rapidapi.proxy-secret=",
        "parserag.rate-limit.starter-requests-per-minute=50"
})
@AutoConfigureMockMvc
class FilterChainWithoutMarketplaceE2eTest {

    private static final String PARSE = "/api/v1/parse";
    private static final String HEALTH = "/api/v1/health";
    private static final String KEY = "cle-interne";

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

    private static ApiKey key(boolean admin) {
        ApiKey k = new ApiKey();
        k.setId(UUID.randomUUID());
        k.setPlan(Plan.STARTER);
        k.setAdmin(admin);
        k.setActive(true);
        return k;
    }

    @Test
    void anOrdinaryInternalKeyStillServesWhenTheMarketplaceIsNotConfigured() throws Exception {
        when(apiKeyRepository.findByKeyHashAndActiveTrue(anyString())).thenReturn(Optional.of(key(false)));

        mvc.perform(multipart(PARSE).file(pdf()).header("X-API-Key", KEY))
                .andExpect(status().isOk());

        verify(pipeline).process(any(), eq(Plan.STARTER));
    }

    @Test
    void anUnknownKeyIsRefused() throws Exception {
        when(apiKeyRepository.findByKeyHashAndActiveTrue(anyString())).thenReturn(Optional.empty());

        mvc.perform(multipart(PARSE).file(pdf()).header("X-API-Key", "clé-inconnue"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INVALID_API_KEY"));

        verify(pipeline, never()).process(any(), any());
    }

    @Test
    void aDownDatabaseYieldsA503RatherThanTheDefaultErrorPage() throws Exception {
        // Le filtre s'exécute hors du DispatcherServlet : sans son catch, l'exception échapperait au
        // GlobalExceptionHandler et le client recevrait la page /error de Spring Boot en 500
        // (issue #37). C'est le genre de garantie qu'un test unitaire vérifie sur le filtre, mais
        // dont seule la chaîne assemblée prouve qu'elle tient bout à bout.
        when(apiKeyRepository.findByKeyHashAndActiveTrue(anyString()))
                .thenThrow(new CannotCreateTransactionException("connexion refusée"));

        mvc.perform(multipart(PARSE).file(pdf()).header("X-API-Key", KEY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("DATABASE_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(503));
    }

    // ── L'endpoint d'administration (issue #37) ───────────────────────────────────────────

    @Test
    void healthIsInvisibleToANonAdminKey() throws Exception {
        // 404 et non 403 : un 403 confirmerait au porteur d'une clé légitime qu'un endpoint
        // d'administration existe à cette adresse.
        when(apiKeyRepository.findByKeyHashAndActiveTrue(anyString())).thenReturn(Optional.of(key(false)));

        mvc.perform(get(HEALTH).header("X-API-Key", KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void healthAnswersAnAdminKey() throws Exception {
        when(apiKeyRepository.findByKeyHashAndActiveTrue(anyString())).thenReturn(Optional.of(key(true)));

        mvc.perform(get(HEALTH).header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.db").exists());
    }

    @Test
    void healthStillNeedsAKeyAtAll() throws Exception {
        mvc.perform(get(HEALTH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("MISSING_API_KEY"));
    }
}
