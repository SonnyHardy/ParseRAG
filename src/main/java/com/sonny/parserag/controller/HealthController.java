package com.sonny.parserag.controller;

import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.exception.ParseRagException;
import com.sonny.parserag.model.response.HealthResponse;
import com.sonny.parserag.service.health.HealthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de diagnostic, <strong>réservé au développeur</strong> (issue #37).
 * <p>
 * GET /api/v1/health
 *   X-API-Key : {clé admin}  ← résolue par ApiKeyFilter, qui pose l'attribut {@code apiKey}
 * <p>
 * Contrairement aux autres endpoints, il n'est pas ouvert à toutes les clés : seule une clé
 * portant {@code admin = true} y accède. Sans clé, {@code ApiKeyFilter} répond 401 en amont ;
 * avec une clé valide mais non-admin, on renvoie <strong>404</strong> et non 403 : un 403
 * confirmerait au porteur d'une clé légitime l'existence d'un endpoint d'administration. Pour
 * qui n'est pas admin, l'endpoint n'existe pas.
 * <p>
 * Réponse : 200 si tous les composants répondent, 503 dès qu'un seul est {@code DOWN} (le corps
 * est le même dans les deux cas, le composant fautif portant {@code DOWN}) — un monitoring lit le
 * code HTTP, un humain lit le corps.
 */
@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
public class HealthController {

    private final HealthService healthService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HealthResponse> health(HttpServletRequest request) {
        requireAdminKey(request);

        HealthResponse health = healthService.check();
        HttpStatus status = health.isUp() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;

        return ResponseEntity.status(status).body(health);
    }

    /**
     * L'attribut {@code apiKey} est posé par {@code ApiKeyFilter}, exécuté avant le
     * DispatcherServlet : ici la clé est donc toujours présente et active. Le {@code null} n'est
     * qu'une garde défensive (filtre désactivé/mal ordonné) et se traite comme un refus.
     * <p>
     * Le refus prend la forme d'un 404 indiscernable d'une URL inexistante — cf. javadoc de classe.
     */
    private void requireAdminKey(HttpServletRequest request) {
        ApiKey apiKey = (ApiKey) request.getAttribute("apiKey");

        if (apiKey == null || !apiKey.isAdmin()) {
            throw new ParseRagException(
                    HttpStatus.NOT_FOUND,
                    "NOT_FOUND",
                    "Endpoint not found"
            );
        }
    }
}
