package com.sonny.parserag.controller;

import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.model.response.ParseResponse;
import com.sonny.parserag.service.pipeline.ParsePipelineService;
import jakarta.servlet.http.HttpServletRequest;
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
public class ParseController {

    private final ParsePipelineService parsePipelineService;

    /**
     * Parse un fichier PDF et retourne des chunks structurés
     * prêts pour l'embedding RAG.
     *
     * @param  file  le fichier PDF en multipart/form-data
     * @return HTTP 200 + ParseResponse en JSON
     */
    @PostMapping(
            value    = "/parse",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ParseResponse> parse(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request
    ) {
        ApiKey apiKey = (ApiKey) request.getAttribute("apiKey");
        log.info("POST /api/v1/parse — '{}' ({} bytes)", file.getOriginalFilename(), file.getSize());

        return ResponseEntity.ok(parsePipelineService.process(file, apiKey));
    }
}