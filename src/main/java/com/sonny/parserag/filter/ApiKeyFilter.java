package com.sonny.parserag.filter;

import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.observability.ParseRagMetrics;
import com.sonny.parserag.observability.ParseRagMetrics.AuthFailure;
import com.sonny.parserag.repository.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "X-API-Key";
    private static final String REQUEST_ATTRIBUTE = "apiKey";

    private final ApiKeyRepository apiKeyRepository;
    private final ObjectMapper objectMapper;
    private final ParseRagMetrics metrics;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String rawKey = request.getHeader(HEADER_NAME);

        if (rawKey == null || rawKey.isBlank()) {
            metrics.authFailure(AuthFailure.MISSING_KEY);
            writeError(response, HttpStatus.UNAUTHORIZED, "MISSING_API_KEY", "Missing X-API-Key header");
            return;
        }

        String keyHash = sha256(rawKey);
        Optional<ApiKey> apiKey;

        try {
            apiKey = apiKeyRepository.findByKeyHashAndActiveTrue(keyHash);
        } catch (DataAccessException | TransactionException e) {
            /*
             * Base injoignable. Les deux familles d'exceptions sont nécessaires : le repository
             * Spring Data est @Transactional(readOnly), donc l'échec survient le plus souvent à
             * l'ouverture de la transaction (CannotCreateTransactionException, une
             * TransactionException) et non sur la requête elle-même (DataAccessException).
             *
             * Sans ce catch, l'exception remonterait hors du DispatcherServlet : ni le
             * GlobalExceptionHandler ni le 503 de HealthController ne la verraient, et le client
             * recevrait la page /error par défaut de Spring Boot en 500. On rend donc ici le même
             * 503 au format standard que celui qu'aurait produit le health check.
             */
            log.error("Lookup de la clé API impossible : base injoignable → 503", e);
            metrics.authFailure(AuthFailure.DB_UNAVAILABLE);
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE",
                    "Database unavailable");
            return;
        }

        if (apiKey.isEmpty()) {
            metrics.authFailure(AuthFailure.INVALID_KEY);
            writeError(response, HttpStatus.FORBIDDEN, "INVALID_API_KEY", "Invalid or inactive API key");
            return;
        }

        request.setAttribute(REQUEST_ATTRIBUTE, apiKey.get());
        filterChain.doFilter(request, response);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Un filtre s'exécute hors du DispatcherServlet : {@code GlobalExceptionHandler} n'y
     * intercepterait pas d'exception. On écrit donc directement le JSON, au format standard
     * {@code {error, message, status}} — le même que le handler global, RateLimitFilter et
     * QuotaEnforcementFilter.
     */
    private void writeError(HttpServletResponse response, HttpStatus status,
                            String errorCode, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of(
                "error", errorCode,
                "message", message,
                "status", status.value()
        ));
    }
}
