package com.sonny.parserag.filter;

import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.model.response.UsageResponse;
import com.sonny.parserag.observability.ParseRagMetrics;
import com.sonny.parserag.service.usage.UsageTrackingService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/**
 * Bloque les parses qui dépasseraient le quota mensuel du plan (issue #13).
 * <p>
 * Placé dans la chaîne Spring Security <em>après</em> {@link ApiKeyFilter} (cf. SecurityConfig),
 * afin de disposer de l'attribut {@code apiKey}. N'enforce que sur {@code POST /api/v1/parse} ;
 * l'incrément du compteur, lui, se fait après un parse réussi (cf. ParseController).
 * <p>
 * Un filtre s'exécute hors du DispatcherServlet : une exception n'y serait pas interceptée par
 * {@code GlobalExceptionHandler}. On écrit donc directement le JSON 429 (même format
 * {@code {error, message, status}} que le handler global), à l'image de {@link ApiKeyFilter}.
 */
@Component
@RequiredArgsConstructor
public class QuotaEnforcementFilter extends OncePerRequestFilter {

    private static final String PARSE_PATH = "/api/v1/parse";
    private static final String REQUEST_ATTRIBUTE = "apiKey";

    private final UsageTrackingService usageTrackingService;
    private final ObjectMapper objectMapper;
    private final ParseRagMetrics metrics;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        // N'enforce que le parse : /usage, /health, etc. restent libres.
        if (!isParseRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        ApiKey apiKey = (ApiKey) request.getAttribute(REQUEST_ATTRIBUTE);
        if (apiKey == null) {
            // ApiKeyFilter garantit normalement une clé ici ; défensif : on laisse passer.
            filterChain.doFilter(request, response);
            return;
        }

        UsageResponse usage = usageTrackingService.currentUsage(apiKey);
        // Relevé du ratio ici plutôt qu'en gauge : l'usage vient d'être lu en base, la mesure est
        // donc gratuite — une gauge imposerait une lecture supplémentaire à chaque scrape.
        metrics.quotaUsageRatio(apiKey.getPlan(), usage.docsUsed(), usage.docsLimit());

        if (usage.docsUsed() >= usage.docsLimit()) {
            metrics.quotaRejected(apiKey.getPlan());
            writeQuotaExceeded(response, usage);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isParseRequest(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && PARSE_PATH.equals(request.getRequestURI());
    }

    private void writeQuotaExceeded(HttpServletResponse response, UsageResponse usage) throws IOException {
        String message = "Monthly quota reached (%d/%d). Resets on %s or upgrade your plan"
                .formatted(usage.docsUsed(), usage.docsLimit(), usage.resetDate());

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of(
                "error", "QUOTA_EXCEEDED",
                "message", message,
                "status", HttpStatus.TOO_MANY_REQUESTS.value()
        ));
    }
}
