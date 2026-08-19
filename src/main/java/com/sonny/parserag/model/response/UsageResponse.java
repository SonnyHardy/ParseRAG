package com.sonny.parserag.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Réponse retournée par GET /api/v1/usage : consommation du cycle courant.
 * <p>
 * Le cycle est « anniversaire » (ancré sur le jour de création de la clé), donc
 * {@code reset_date} n'est pas le 1er du mois mais la prochaine occurrence de ce jour.
 */
@Schema(name = "UsageResponse", description = "Quota consumption for the current billing cycle.")
public record UsageResponse(

        @Schema(description = "Plan attached to the key.",
                allowableValues = {"free", "starter", "pro", "scale"}, example = "free")
        String plan,

        @JsonProperty("docs_used")
        @Schema(description = "Documents parsed successfully this cycle.", example = "37")
        int docsUsed,

        @JsonProperty("docs_limit")
        @Schema(description = "Documents allowed per cycle on this plan.", example = "100")
        int docsLimit,

        @JsonProperty("docs_remaining")
        @Schema(description = "Documents left before parse calls are rejected with QUOTA_EXCEEDED.",
                example = "63")
        int docsRemaining,

        @JsonProperty("reset_date")
        @Schema(description = "Date the counter resets (ISO 8601). Anchored on the day the key was "
                + "created, not the first of the month.", example = "2026-09-14")
        String resetDate,

        @Schema(description = "Cycle identifier, the year-month in which the cycle started.",
                example = "2026-08")
        String period
) {}
