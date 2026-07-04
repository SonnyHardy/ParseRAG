package com.sonny.parserag.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Réponse retournée par GET /api/v1/usage : consommation du cycle courant.
 * <p>
 * Le cycle est « anniversaire » (ancré sur le jour de création de la clé), donc
 * {@code reset_date} n'est pas le 1er du mois mais la prochaine occurrence de ce jour.
 */
public record UsageResponse(

        String plan,

        @JsonProperty("docs_used")
        int docsUsed,

        @JsonProperty("docs_limit")
        int docsLimit,

        @JsonProperty("docs_remaining")
        int docsRemaining,

        @JsonProperty("reset_date")
        String resetDate,

        String period
) {}
