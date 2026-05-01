package com.sonny.parserag.filter;

import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.repository.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "X-API-Key";
    private static final String REQUEST_ATTRIBUTE = "apiKey";

    private final ApiKeyRepository apiKeyRepository;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/v1/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String rawKey = request.getHeader(HEADER_NAME);

        if (rawKey == null || rawKey.isBlank()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing X-API-Key header");
            return;
        }

        String keyHash = sha256(rawKey);
        Optional<ApiKey> apiKey = apiKeyRepository.findByKeyHashAndActiveTrue(keyHash);

        if (apiKey.isEmpty()) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "Invalid or inactive API key");
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

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of("error", message));
    }
}
