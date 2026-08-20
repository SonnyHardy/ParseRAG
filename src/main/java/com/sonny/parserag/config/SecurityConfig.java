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
     * Désactive l'auto-registration servlet de Spring Boot pour ApiKeyFilter.
     * Le filtre est injecté dans la chaîne Spring Security via addFilterBefore,
     * pas comme filtre servlet standalone — évite une double exécution.
     */
    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilterRegistration(ApiKeyFilter filter) {
        FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Même traitement pour RateLimitFilter : injecté dans la chaîne Spring Security via
     * addFilterAfter, on désactive son auto-registration servlet pour éviter une double exécution.
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Même traitement pour RapidApiProxyFilter : injecté dans la chaîne Spring Security via
     * addFilterBefore, on désactive son auto-registration servlet pour éviter une double exécution.
     */
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
