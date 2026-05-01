package com.sonny.parserag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configuration Spring Security.
 * <p>
 * <p> Sprint 1 : toutes les requêtes sont autorisées (test facile).
 * <p> Sprint 4 : remplacé par la validation du header X-API-Key
 *            via ApiKeyFilter (issue #4).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
                // Pas de sessions — API REST stateless
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Pas de CSRF — API REST, pas de formulaires navigateur
                .csrf(AbstractHttpConfigurer::disable)
                // Sprint 1 : tout autorisé
                //Todo: Sprint 4 : remplacer par .anyRequest().authenticated()
                .authorizeHttpRequests(auth ->
                        auth.anyRequest().permitAll());

        return http.build();
    }
}