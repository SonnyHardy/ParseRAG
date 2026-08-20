package com.sonny.parserag.controller;

import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.exception.GlobalExceptionHandler.ErrorResponse;
import com.sonny.parserag.filter.RequestAttributes;
import com.sonny.parserag.model.response.ParseResponse;
import com.sonny.parserag.service.pipeline.ParseConcurrencyLimiter;
import com.sonny.parserag.service.pipeline.ParsePipelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Endpoint principal de ParseRAG.
 * <p>
 * POST /v1/parse
 *   Content-Type : multipart/form-data
 *   X-API-Key    : {clé}  ← ajoutée par ApiKeyFilter
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Parsing", description = "Turn a PDF into RAG-ready chunks.")
public class ParseController {

    private final ParsePipelineService parsePipelineService;
    private final ParseConcurrencyLimiter concurrencyLimiter;

    /**
     * Parse un fichier PDF et retourne des chunks structurés
     * prêts pour l'embedding RAG.
     *
     * @param  file  le fichier PDF en multipart/form-data
     * @return HTTP 200 + ParseResponse en JSON
     */
    @Operation(
            summary = "Parse a PDF into chunks",
            description = """
                    Uploads a PDF and returns embedding-ready chunks. The pipeline extracts text \
                    column-aware, strips repeated headers and footers, extracts tables as \
                    structured JSON, and falls back to a vision model on scanned pages.

                    Limits: 50 MB per file, and a page cap that depends on your plan (100 pages on \
                    Free, up to 1000 on Scale). A successful call counts as one request against your \
                    subscription quota.""",
            operationId = "parsePdf"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PDF parsed successfully."),
            @ApiResponse(responseCode = "400",
                    description = "MISSING_FILE, INVALID_FILE_FORMAT (bad PDF signature) "
                            + "or PDF_UNREADABLE (corrupted or encrypted file).",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "MISSING_API_KEY — no X-API-Key header.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "INVALID_API_KEY (unknown or inactive key), INVALID_PROXY_SECRET "
                            + "(the call did not come through the RapidAPI marketplace) or MARKETPLACE_REQUIRED "
                            + "(a direct key was used while the marketplace serves traffic).",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "413", description = "FILE_TOO_LARGE — over the 50 MB limit.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "415",
                    description = "INVALID_FILE_FORMAT or UNSUPPORTED_MEDIA_TYPE — the upload is not a PDF.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422",
                    description = "DOCUMENT_TOO_LONG — more pages than your plan allows.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429",
                    description = "RATE_LIMIT_EXCEEDED — the service-wide traffic guard tripped; "
                            + "wait Retry-After seconds. Plan quotas are enforced by the marketplace "
                            + "proxy before the request reaches ParseRAG.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR — unexpected failure.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503",
                    description = "SERVICE_BUSY (the server is at capacity, retry shortly) or "
                            + "DATABASE_UNAVAILABLE (the key could not be verified).",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(
            value    = "/parse",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ParseResponse> parse(
            // Pas de @Schema ici : annoter le parametre remplace le schema du *corps* entier, et
            // springdoc rend alors un binaire brut au lieu d'un formulaire portant une partie
            // « file » — un client genere depuis la spec posterait le mauvais corps.
            @RequestParam("file") @NotNull MultipartFile file,
            HttpServletRequest request
    ) {
        // Posé par RapidApiProxyFilter (trafic public) ou par ApiKeyFilter (clé interne) ; le
        // pipeline n'a pas à savoir lequel des deux. Le null est une garde défensive — filtre
        // désactivé ou mal ordonné — et se traite comme le plan le plus restrictif.
        Plan plan = (Plan) request.getAttribute(RequestAttributes.PLAN);
        if (plan == null) plan = Plan.FREE;

        log.debug("POST /api/v1/parse — '{}' ({} bytes), plan {}",
                file.getOriginalFilename(), file.getSize(), plan);

        // Borne de concurrence (issue #56) : la memoire est la ressource rare, elle se compte en
        // parses simultanes. Au-dela, refus franc en 503 plutot qu'une file d'attente invisible.
        Plan effectivePlan = plan;
        return ResponseEntity.ok(
                concurrencyLimiter.withSlot(() -> parsePipelineService.process(file, effectivePlan)));
    }
}
