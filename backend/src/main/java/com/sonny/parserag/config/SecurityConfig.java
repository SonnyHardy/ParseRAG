package com.sonny.parserag.config;

import com.sonny.parserag.filter.ApiKeyFilter;
import com.sonny.parserag.filter.RapidApiProxyFilter;
import com.sonny.parserag.filter.RateLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   RapidApiProxyFilter rapidApiProxyFilter,
                                                   ApiKeyFilter apiKeyFilter,
                                                   RateLimitFilter rateLimitFilter) {
        http
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Pas de CSRF : l'API est stateless, pas de session, pas de cookie.
                .csrf(AbstractHttpConfigurer::disable)
                // RapidAPI d'abord : il authentifie le trafic public et pose le plan. ApiKeyFilter
                // ne réclame une clé interne que si le proxy n'a rien posé (admin, dev, transition).
                .addFilterBefore(rapidApiProxyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(apiKeyFilter, RapidApiProxyFilter.class)
                // Garde de débit en dernier : elle ne dépend plus d'aucune identité (issue #54),
                // mais la placer après l'authentification évite qu'un appelant non authentifié
                // consomme les jetons de ceux qui le sont.
                .addFilterAfter(rateLimitFilter, ApiKeyFilter.class)
                .authorizeHttpRequests(auth ->
                        auth.anyRequest().permitAll());

        return http.build();
    }

    /**
     * Désactive l'auto-registration servlet de Spring Boot pour ApiKeyFilter, qui est injecté dans
     * la chaîne Spring Security et n'a rien à faire en filtre servlet autonome.
     * <p>
     * <strong>Ce n'est pas ce bean qui empêche la double exécution</strong> (mesuré en #59, en le
     * réactivant délibérément : aucun changement de comportement) — c'est {@code OncePerRequestFilter},
     * qui marque la requête et saute la seconde passe. Un {@code FilterRegistrationBean} sans ordre
     * explicite se place d'ailleurs en {@code LOWEST_PRECEDENCE}, donc après la chaîne de sécurité.
     * Ce qu'on protège ici est l'avenir : un ordre explicite placerait le filtre <em>avant</em>
     * l'authentification, et le limiteur de débit cadencerait alors des requêtes sans identité.
     */
    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilterRegistration(ApiKeyFilter filter) {
        FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** Même traitement pour RateLimitFilter — mêmes raisons que ci-dessus. */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** Même traitement pour RapidApiProxyFilter — mêmes raisons que ci-dessus. */
    @Bean
    public FilterRegistrationBean<RapidApiProxyFilter> rapidApiProxyFilterRegistration(RapidApiProxyFilter filter) {
        FilterRegistrationBean<RapidApiProxyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
