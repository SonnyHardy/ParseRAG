package com.sonny.parserag.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.servers.ServerVariable;
import io.swagger.v3.oas.models.servers.ServerVariables;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Description globale de l'API pour la spécification OpenAPI (issue #16) : identité, serveur,
 * schéma d'authentification. Les endpoints eux-mêmes sont décrits par les annotations posées sur
 * les controllers.
 * <p>
 * <strong>Désactivée par défaut</strong>, comme tout springdoc : {@code springdoc.api-docs.enabled}
 * vaut {@code false} dans {@code application.yaml}. La spécification publiée est le snapshot
 * {@code docs/openapi.json}, régénéré en lançant l'application avec le profil {@code docs} — voir
 * la section « Regenerating the OpenAPI spec » du README. Deux raisons de ne pas l'exposer en
 * production : {@code ApiKeyFilter} filtre <em>toutes</em> les requêtes sans chemin exempté (issue
 * #37), donc {@code /v3/api-docs} répondrait 401 sans clé ; et une API commerciale n'a pas besoin
 * de servir sa propre Swagger UI, ses consommateurs lisant la spec depuis RapidAPI.
 * <p>
 * La condition porte sur la même propriété que springdoc : sans elle, ce bean serait construit
 * même api-docs désactivée — inoffensif mais inutile.
 */
@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OpenApiConfig {

    /** Nom du schéma de sécurité, référencé par les opérations dans la spec générée. */
    private static final String API_KEY_SCHEME = "ApiKeyAuth";

    private static final String API_KEY_HEADER = "X-API-Key";

    private final String version;

    public OpenApiConfig(@Value("${parserag.info.version:unknown}") String version) {
        this.version = version;
    }

    @Bean
    public OpenAPI parseRagOpenAPI() {
        return new OpenAPI()
                .info(apiInfo())
                .servers(List.of(templatedServer()))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME, apiKeyScheme()))
                // Sécurité globale : chaque endpoint exige la clé, aucun n'est ouvert.
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }

    private Info apiInfo() {
        return new Info()
                .title("ParseRAG API")
                .version(version)
                .description("""
                        ParseRAG turns any PDF into clean, structured JSON for your RAG pipeline.

                        Upload a PDF, get back embedding-ready chunks: text is extracted \
                        column-aware, repeated headers and footers are stripped, tables come back \
                        as structured JSON, and scanned pages fall back to a vision model. Every \
                        chunk carries a confidence score and a `manual_review_needed` flag, so a \
                        low-quality page never enters your index unnoticed.

                        Authentication is by API key: send it in the `X-API-Key` header on every \
                        request. Rate limits and monthly quotas depend on your plan.""")
                .contact(new Contact().name("ParseRAG").url("https://github.com/SonnyHardy/ParseRAG"))
                .license(new License().name("Proprietary"));
    }

    /**
     * Serveur laissé sous forme de variable : le déploiement public n'est pas encore en ligne
     * (chantier distinct du Sprint 4). Le défaut pointe l'exécution locale, ce qui rend la spec
     * directement jouable depuis un poste de développement.
     */
    private Server templatedServer() {
        ServerVariable baseUrl = new ServerVariable()
                ._default("http://localhost:8080")
                .description("Base URL of your ParseRAG deployment. Defaults to a local run.");

        return new Server()
                .url("{baseUrl}")
                .description("ParseRAG deployment")
                .variables(new ServerVariables().addServerVariable("baseUrl", baseUrl));
    }

    private SecurityScheme apiKeyScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name(API_KEY_HEADER)
                .description("API key issued with your plan. Sent on every request, including "
                        + "GET /api/v1/usage.");
    }
}
