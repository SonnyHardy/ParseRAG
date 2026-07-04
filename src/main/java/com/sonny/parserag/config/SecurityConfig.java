package com.sonny.parserag.config;

import com.sonny.parserag.filter.ApiKeyFilter;
import com.sonny.parserag.filter.QuotaEnforcementFilter;
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
                                                   ApiKeyFilter apiKeyFilter,
                                                   QuotaEnforcementFilter quotaEnforcementFilter) {
        http
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Pas de CSRF : l'API est stateless, pas de session, pas de cookie.
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                // Quota après ApiKeyFilter : a besoin de l'attribut apiKey qu'il pose.
                .addFilterAfter(quotaEnforcementFilter, ApiKeyFilter.class)
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
     * Même traitement pour QuotaEnforcementFilter : injecté dans la chaîne Spring Security via
     * addFilterAfter, on désactive son auto-registration servlet pour éviter une double exécution.
     */
    @Bean
    public FilterRegistrationBean<QuotaEnforcementFilter> quotaEnforcementFilterRegistration(QuotaEnforcementFilter filter) {
        FilterRegistrationBean<QuotaEnforcementFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
