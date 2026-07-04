package com.sonny.parserag.controller;

import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.model.response.UsageResponse;
import com.sonny.parserag.service.usage.UsageTrackingService;
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
public class UsageController {

    private final UsageTrackingService usageTrackingService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UsageResponse> usage(HttpServletRequest request) {
        ApiKey apiKey = (ApiKey) request.getAttribute("apiKey");
        return ResponseEntity.ok(usageTrackingService.currentUsage(apiKey));
    }
}
