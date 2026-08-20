package com.sonny.parserag.filter;

import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.observability.ParseRagMetrics;
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
import java.util.Map;

/**
 * Garde de débit par consommateur, au débit de son plan (issues #14, #54).
 * <p>
 * Le filtre s'exécute après l'authentification, dont il tire les deux informations dont il a
 * besoin : l'identité de l'appelant, qui désigne son bucket, et son plan, qui en fixe la capacité.
 * Il fonctionne indifféremment sur les deux chemins d'authentification — voir
 * {@link RateLimitService}.
 * <p>
 * <strong>Pas d'en-têtes {@code X-RateLimit-*} sur les réponses normales.</strong> Ils annonçaient
 * le budget d'un plan ; ce budget est désormais celui de RapidAPI, qui pose ses propres
 * {@code x-ratelimit-requests-remaining} / {@code -reset}. En publier d'autres, portant un plafond
 * d'infrastructure sans rapport avec l'abonnement, ne ferait qu'induire le client en erreur. Seul
 * le {@code 429} porte un {@code Retry-After}, qui lui reste actionnable.
 */
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;
    private final ParseRagMetrics metrics;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        Plan plan = planOf(request);
        ConsumptionProbe probe = rateLimitService.tryConsume(consumerOf(request), plan);

        if (!probe.isConsumed()) {
            metrics.rateLimitRejected(plan);
            long retryAfter = Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds();
            writeRateLimited(response, rateLimitService.limitForPlan(plan), retryAfter);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Plan applicable, posé par le filtre qui a authentifié la requête. Le repli sur le plan le
     * plus restrictif est une garde défensive : une requête sans plan ne doit pas hériter du débit
     * le plus large.
     */
    private static Plan planOf(HttpServletRequest request) {
        return request.getAttribute(RequestAttributes.PLAN) instanceof Plan plan ? plan : Plan.FREE;
    }

    /**
     * Identité de l'appelant, dans l'ordre de confiance : celle annoncée par RapidAPI — lue
     * seulement après validation du secret, donc digne de foi — puis l'identifiant de la clé
     * interne. Le préfixe évite qu'un utilisateur RapidAPI nommé comme un UUID de clé ne partage
     * son bucket avec elle.
     * <p>
     * Le repli {@code anonymous} est une garde défensive : la chaîne garantit qu'une requête
     * arrivée ici est authentifiée. Il mutualise volontairement le bucket, faute de quoi une
     * requête non identifiée s'en verrait offrir un neuf à chaque appel.
     */
    private static String consumerOf(HttpServletRequest request) {
        Object user = request.getAttribute(RequestAttributes.RAPIDAPI_USER);
        if (user instanceof String s && !s.isBlank()) return "rapidapi:" + s;

        Object apiKey = request.getAttribute(RequestAttributes.API_KEY);
        if (apiKey instanceof ApiKey k && k.getId() != null) return "key:" + k.getId();

        return "anonymous";
    }

    /**
     * Un filtre s'exécute hors du DispatcherServlet : on écrit le JSON au format standard
     * {@code {error, message, status}}, comme les autres filtres et le handler global.
     */
    private void writeRateLimited(HttpServletResponse response, int limit, long retryAfterSeconds) throws IOException {
        String message = "Rate limit reached (%d req/min). Retry after %d seconds"
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
