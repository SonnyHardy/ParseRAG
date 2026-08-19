package com.sonny.parserag.controller;

import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.exception.GlobalExceptionHandler.ErrorResponse;
import com.sonny.parserag.model.response.ParseResponse;
import com.sonny.parserag.service.pipeline.ParsePipelineService;
import com.sonny.parserag.service.usage.UsageTrackingService;
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
    private final UsageTrackingService usageTrackingService;

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
                    Free, up to 1000 on Scale). A successful call consumes one document from your \
                    monthly quota; failed calls do not.""",
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
            @ApiResponse(responseCode = "403", description = "INVALID_API_KEY — unknown or inactive key.",
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
                    description = "RATE_LIMIT_EXCEEDED (see Retry-After) or QUOTA_EXCEEDED "
                            + "(monthly document quota reached).",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR — unexpected failure.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503",
                    description = "DATABASE_UNAVAILABLE — the key could not be verified.",
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
        ApiKey apiKey = (ApiKey) request.getAttribute("apiKey");
        log.debug("POST /api/v1/parse — '{}' ({} bytes)", file.getOriginalFilename(), file.getSize());

        ParseResponse response = parsePipelineService.process(file, apiKey);

        // Incrément du quota seulement sur parse réussi (les exceptions du pipeline propagent avant).
        if (apiKey != null) {
            usageTrackingService.recordSuccessfulParse(apiKey);
        }

        return ResponseEntity.ok(response);
    }
}
