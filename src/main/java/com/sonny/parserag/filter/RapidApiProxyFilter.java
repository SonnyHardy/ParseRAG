package com.sonny.parserag.filter;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.observability.ParseRagMetrics;
import com.sonny.parserag.observability.ParseRagMetrics.AuthFailure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Authentification des requêtes venues de la place de marché RapidAPI (issue #54).
 * <p>
 * RapidAPI valide la clé du consommateur, applique le quota de son plan et facture ; il transmet
 * le résultat en en-têtes. Ce filtre ne fait donc que deux choses : vérifier que la requête vient
 * bien du proxy, et traduire le plan annoncé en {@link Plan} interne.
 *
 * <p><strong>Pourquoi le secret est la pièce maîtresse.</strong> Notre origine reste joignable en
 * direct. Sans cette vérification, n'importe qui contourne la place de marché : pas de quota, pas
 * de facturation, pas d'analytics. {@code X-RapidAPI-Proxy-Secret} est ajouté par le proxy sur
 * chaque requête et n'est connu que de lui et de nous.
 *
 * <p><strong>Ordre : secret d'abord, plan ensuite.</strong> Les en-têtes de plan et d'identité sont
 * du texte libre qu'un appelant direct peut forger — {@code X-RapidAPI-Subscription: MEGA} est à la
 * portée de n'importe qui. Ils ne sont donc lus qu'<em>après</em> validation du secret, et un plan
 * inconnu retombe sur le plus restrictif ({@link AppProperties.RapidApi#planFor}) : on échoue fermé.
 *
 * <p><strong>Cohabitation avec {@code ApiKeyFilter}.</strong> Trois cas, et un seul chemin par
 * requête :
 * <ul>
 *   <li>en-tête de secret <em>absent</em> → on laisse passer vers {@code ApiKeyFilter}, qui exige
 *       une clé interne. C'est le chemin du développement, de l'administration
 *       ({@code GET /api/v1/health}) et de la transition tant que le listing n'est pas publié ;</li>
 *   <li>secret <em>présent et valide</em> → la requête est authentifiée ici, {@code ApiKeyFilter}
 *       la laisse passer sans réclamer de clé ;</li>
 *   <li>secret <em>présent et faux</em> → refus immédiat. Ne pas retomber sur la clé interne dans
 *       ce cas est délibéré : quelqu'un qui présente un mauvais secret n'est pas un appelant
 *       légitime, et l'enchaînement offrirait une seconde chance à qui sonde nos portes.</li>
 * </ul>
 *
 * <p>Aucune I/O : la vérification est une comparaison de chaînes. C'est le gain collatéral du
 * passage par la place de marché — {@code ApiKeyFilter} fait, lui, une lecture en base à chaque
 * requête.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RapidApiProxyFilter extends OncePerRequestFilter {

    static final String SECRET_HEADER       = "X-RapidAPI-Proxy-Secret";
    static final String SUBSCRIPTION_HEADER = "X-RapidAPI-Subscription";
    static final String USER_HEADER         = "X-RapidAPI-User";

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final ParseRagMetrics metrics;

    /**
     * La sonde de vivacite de l'orchestrateur ne peut pas s'authentifier : elle est le seul chemin
     * exempte, et sa portee est bornee a un UP/DOWN nu (cf. {@link ProbePaths}).
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return ProbePaths.isLivenessProbe(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        AppProperties.RapidApi config = appProperties.getRapidapi();
        String presented = request.getHeader(SECRET_HEADER);

        if (presented == null || presented.isBlank()) {
            filterChain.doFilter(request, response);   // → chemin clé interne
            return;
        }

        /*
         * Secret présenté alors que l'intégration n'est pas configurée : refus. Accepter
         * reviendrait à ouvrir l'origine dès qu'un déploiement oublie la variable
         * d'environnement — exactement la panne silencieuse que ce filtre existe pour empêcher.
         */
        if (!config.isConfigured() || !matches(presented, config)) {
            metrics.authFailure(AuthFailure.INVALID_PROXY_SECRET);
            log.warn("Appel rejeté : X-RapidAPI-Proxy-Secret invalide (origine appelée en direct ?)");
            writeError(response, HttpStatus.FORBIDDEN, "INVALID_PROXY_SECRET",
                    "Requests must go through the RapidAPI marketplace");
            return;
        }

        Plan plan = config.planFor(request.getHeader(SUBSCRIPTION_HEADER));
        request.setAttribute(RequestAttributes.PLAN, plan);
        request.setAttribute(RequestAttributes.RAPIDAPI_USER, request.getHeader(USER_HEADER));

        log.debug("Requête RapidAPI — abonnement '{}' → plan {}",
                request.getHeader(SUBSCRIPTION_HEADER), plan);

        filterChain.doFilter(request, response);
    }

    /**
     * Comparaison en temps constant, contre le secret courant puis le précédent.
     * <p>
     * {@code MessageDigest.isEqual} ne court-circuite pas au premier octet différent : une
     * comparaison naïve laisserait fuir la longueur du préfixe correct par le temps de réponse,
     * et un secret se reconstruit octet par octet à partir de cette fuite.
     */
    private boolean matches(String presented, AppProperties.RapidApi config) {
        return constantTimeEquals(presented, config.getProxySecret())
                || constantTimeEquals(presented, config.getPreviousProxySecret());
    }

    private boolean constantTimeEquals(String presented, String expected) {
        if (expected == null || expected.isBlank()) return false;
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Un filtre s'exécute hors du DispatcherServlet : le {@code GlobalExceptionHandler} n'y
     * intercepterait pas d'exception. On écrit donc le JSON au format standard
     * {@code {error, message, status}}, comme les autres filtres.
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
