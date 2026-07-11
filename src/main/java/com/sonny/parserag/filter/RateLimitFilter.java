package com.sonny.parserag.filter;

import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.service.ratelimit.RateLimitService;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Rate limiting par API key (issue #14).
 * <p>
 * Placé dans la chaîne Spring Security <em>après</em> {@link ApiKeyFilter} (a besoin du plan porté
 * par l'attribut {@code apiKey}) et <em>avant</em> {@link QuotaEnforcementFilter} : le contrôle de
 * débit est un check mémoire bon marché, on le fait avant le check quota en base.
 * <p>
 * Pose sur <strong>chaque</strong> réponse les headers {@code X-RateLimit-Limit/-Remaining/-Reset}.
 * Si le bucket est vide, renvoie {@code 429} avec {@code Retry-After} et un corps JSON au même
 * format {@code {error, message, status}} que {@link QuotaEnforcementFilter} et le handler global
 * (un filtre s'exécute hors du DispatcherServlet : l'exception n'y serait pas interceptée).
 */
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String REQUEST_ATTRIBUTE = "apiKey";

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/v1/health");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        ApiKey apiKey = (ApiKey) request.getAttribute(REQUEST_ATTRIBUTE);
        if (apiKey == null) {
            // ApiKeyFilter garantit normalement une clé ici ; défensif : on laisse passer.
            filterChain.doFilter(request, response);
            return;
        }

        int limit = rateLimitService.limitForPlan(apiKey.getPlan());
        ConsumptionProbe probe = rateLimitService.tryConsume(apiKey);

        long resetEpochSeconds = Instant.now().getEpochSecond()
                + Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds();

        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, probe.getRemainingTokens())));
        response.setHeader("X-RateLimit-Reset", String.valueOf(resetEpochSeconds));

        if (!probe.isConsumed()) {
            long retryAfter = Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds();
            writeRateLimited(response, limit, retryAfter);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeRateLimited(HttpServletResponse response, int limit, long retryAfterSeconds) throws IOException {
        String message = "Rate limit exceeded (%d req/min). Retry after %d seconds"
                .formatted(limit, retryAfterSeconds);

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of(
                "error", "RATE_LIMIT_EXCEEDED",
                "message", message,
                "status", HttpStatus.TOO_MANY_REQUESTS.value()
        ));
    }
}
