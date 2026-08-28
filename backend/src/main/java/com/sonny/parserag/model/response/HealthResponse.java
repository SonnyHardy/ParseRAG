package com.sonny.parserag.model.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/**
 * Réponse retournée par GET /api/v1/health (issue #37) : identité du build + état des dépendances
 * que l'application ne peut pas garantir elle-même.
 * <p>
 * Il n'y a volontairement <strong>pas</strong> de champ « application » : le fait que cette réponse
 * soit servie prouve déjà que la JVM tourne, que le contexte Spring a démarré et que la chaîne de
 * filtres répond. Un composant qui s'auto-diagnostique ne peut renvoyer que {@code UP}. Ne sont
 * donc listés ici que les composants réellement faillibles.
 * <p>
 * Exemple :
 * <pre>
 * {
 *   "status": "UP",
 *   "db": "UP",
 *   "flyway": "UP",
 *   "disk": "UP",
 *   "timestamp": "2026-07-11T15:30:00Z",
 *   "application": "ParseRAG",
 *   "version": "0.0.1-SNAPSHOT"
 * }
 * </pre>
 *
 * @param status      état global : {@code UP} si tous les composants répondent, {@code DOWN} sinon
 * @param db          résultat du ping base ({@code UP} / {@code DOWN})
 * @param flyway      état du schéma : {@code DOWN} si une migration est en attente ou en échec —
 *                    le binaire tourne alors sur un schéma qui n'est pas celui qu'il attend
 * @param disk        espace libre au-dessus du plancher {@code parserag.health.min-free-disk-mb}
 * @param timestamp   horodatage serveur en UTC (sérialisé en ISO-8601, ex. {@code 2026-07-11T15:30:00Z})
 * @param application nom de l'application (cf. {@code parserag.info.name})
 * @param version     version du build (cf. {@code parserag.info.version})
 */
public record HealthResponse(
        String status,
        String db,
        String flyway,
        String disk,

        /*
         * shape = STRING : verrouille la sortie sur l'ISO-8601 ("2026-07-11T15:30:00Z") plutôt que
         * de dépendre du réglage WRITE_DATES_AS_TIMESTAMPS du mapper, qui rendrait un epoch numérique.
         */
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant timestamp,

        String application,
        String version
) {

    /** Valeur des champs d'état : l'endpoint n'expose que UP / DOWN. */
    public static final String UP = "UP";
    public static final String DOWN = "DOWN";

    /** Traduit un booléen en valeur d'état, pour éviter les ternaires UP/DOWN à répétition. */
    public static String state(boolean up) {
        return up ? UP : DOWN;
    }

    /**
     * Le statut global est la conjonction des composants : un seul {@code DOWN} fait tomber
     * l'ensemble (le contrôleur traduit alors en HTTP 503).
     * <p>
     * {@code @JsonIgnore} obligatoire : sans elle, Jackson voit un getter et ajoute un champ
     * {@code "up"} au corps de réponse.
     */
    @JsonIgnore
    public boolean isUp() {
        return UP.equals(status);
    }
}
