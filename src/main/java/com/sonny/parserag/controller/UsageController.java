package com.sonny.parserag.controller;

import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.exception.GlobalExceptionHandler.ErrorResponse;
import com.sonny.parserag.model.response.UsageResponse;
import com.sonny.parserag.service.usage.UsageTrackingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de consultation de quota.
 * <p>
 * GET /api/v1/usage
 *   X-API-Key : {clé}  ← ajoutée par ApiKeyFilter
 * <p>
 * Non soumis à l'enforcement quota : le client doit toujours pouvoir consulter sa conso,
 * même une fois le quota atteint.
 */
@RestController
@RequestMapping("/api/v1/usage")
@RequiredArgsConstructor
@Tag(name = "Usage", description = "Check how much of your monthly quota is left.")
public class UsageController {

    private final UsageTrackingService usageTrackingService;

    @Operation(
            summary = "Get current usage",
            description = """
                    Returns the documents consumed on the current billing cycle, the plan limit,                     and the date the counter resets.

                    The cycle is anniversary-based: it is anchored on the day your key was created,                     so `reset_date` is the next occurrence of that day, not the first of the month.                     This endpoint is never blocked by the quota — you can always read your usage,                     even once the quota is reached.""",
            operationId = "getUsage"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current cycle usage."),
            @ApiResponse(responseCode = "401", description = "MISSING_API_KEY — no X-API-Key header.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "INVALID_API_KEY — unknown or inactive key.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429",
                    description = "RATE_LIMIT_EXCEEDED — this endpoint counts against your rate limit.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503",
                    description = "DATABASE_UNAVAILABLE — the key could not be verified.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UsageResponse> usage(HttpServletRequest request) {
        ApiKey apiKey = (ApiKey) request.getAttribute("apiKey");
        return ResponseEntity.ok(usageTrackingService.currentUsage(apiKey));
    }
}
