package com.sonny.parserag.filter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Chemins exemptés de la chaîne de filtres (issue #58).
 *
 * <p>Un seul, et c'est délibérément le strict minimum : <strong>{@code /actuator/health}</strong>,
 * la sonde de vivacité de l'orchestrateur. Elle ne peut pas s'authentifier — la plateforme ne
 * connaît que le <em>hash</em> de la clé d'administration, jamais la clé — et un conteneur dont la
 * sonde échoue est redémarré en boucle.
 *
 * <p><strong>Ce que cela n'ouvre pas.</strong> L'endpoint rend un {@code {"status":"UP"}} nu :
 * {@code management.endpoint.health.show-details} vaut {@code never}, donc ni base, ni disque, ni
 * version n'en sortent. Il n'apprend à personne davantage qu'un test de port TCP. L'endpoint métier
 * {@code /api/v1/health}, lui, reste réservé à l'administrateur (issue #37) : c'est celui qui
 * détaille l'état des dépendances, et l'exemption ne le concerne pas.
 *
 * <p><strong>Pourquoi l'exempter aussi du rate limiting.</strong> Sans identité, la sonde
 * partagerait le bucket {@code anonymous} : il suffirait de saturer ce bucket pour faire échouer la
 * sonde, donc pour faire redémarrer le conteneur. Le limiteur deviendrait l'arme du déni de
 * service au lieu d'en protéger.
 */
public final class ProbePaths {

    private ProbePaths() {
    }

    /** Sonde de vivacité, seul chemin non authentifié de l'application. */
    public static final String LIVENESS = "/actuator/health";

    /**
     * Comparaison exacte, jamais un préfixe : {@code startsWith} exempterait
     * {@code /actuator/health/../metrics} et tout ce que l'orchestrateur n'a pas demandé.
     */
    public static boolean isLivenessProbe(HttpServletRequest request) {
        return LIVENESS.equals(request.getRequestURI());
    }
}
