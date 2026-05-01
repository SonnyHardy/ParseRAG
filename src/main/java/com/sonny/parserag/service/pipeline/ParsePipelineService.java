package com.sonny.parserag.service.pipeline;

import com.sonny.parserag.exception.ParseRagException;
import com.sonny.parserag.model.domain.Chunk;
import com.sonny.parserag.model.response.ParseResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrateur central du pipeline de parsing ParseRAG.
 * <p>
 * Progression par sprint :
 *   Sprint 1 → validation fichier + métadonnées PDF (ici)
 *   Sprint 2 → extraction texte, chunking, score de confiance
 *   Sprint 3 → extraction tableaux, fallback GPT-4o mini
 *   Sprint 4 → tracking usage, rate limiting
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParsePipelineService {

    private static final String PDF_CONTENT_TYPE  = "application/pdf";
    private static final String PDF_MAGIC_BYTES   = "%PDF";
    private static final long   MAX_FILE_SIZE      = 50L * 1024 * 1024; // 50 MB
    private static final int    MAX_PAGES          = 500;


    /**
     * Point d'entrée du pipeline.
     * Sprint 1 : valide le fichier et retourne les métadonnées PDF.
     */
    public ParseResponse process(MultipartFile file) {
        long startTime = System.currentTimeMillis();

        log.info("Pipeline start — file: '{}', size: {} bytes",
                file.getOriginalFilename(), file.getSize());

        // ── Étape 1 : Validation du fichier ──────────────────────────────────
        validateFile(file);

        // ── Étape 2 : Ouverture PDF et lecture des métadonnées ────────────────
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {

            int pageCount = document.getNumberOfPages();

            if (pageCount > MAX_PAGES) {
                throw new ParseRagException(
                        HttpStatus.BAD_REQUEST,
                        "DOCUMENT_TOO_LONG",
                        "Document has %d pages. Maximum allowed: %d."
                                .formatted(pageCount, MAX_PAGES)
                );
            }

            String language   = detectLanguage(document.getDocumentCatalog());
            String documentId = generateDocumentId();
            long   processingMs = System.currentTimeMillis() - startTime;

            // Chunks vides en Sprint 1 — remplis à partir du Sprint 2
            List<Chunk> chunks = Collections.emptyList();

            log.info("Pipeline done — docId: {}, pages: {}, lang: {}, time: {}ms",
                    documentId, pageCount, language, processingMs);

            return ParseResponse.ok(documentId, pageCount, language, processingMs, chunks);

        } catch (ParseRagException e) {
            throw e; // laisser remonter au GlobalExceptionHandler
        } catch (IOException e) {
            log.error("Failed to open PDF: {}", file.getOriginalFilename(), e);
            throw new ParseRagException(
                    HttpStatus.BAD_REQUEST,
                    "PDF_UNREADABLE",
                    "The PDF could not be read. It may be corrupted or password-protected."
            );
        }
    }

    // ── Helpers privés ────────────────────────────────────────────────────────

    private void validateFile(MultipartFile file) {

        // 1. Fichier absent ou vide
        if (file == null || file.isEmpty()) {
            throw new ParseRagException(
                    HttpStatus.BAD_REQUEST,
                    "MISSING_FILE",
                    "No file provided. Please attach a PDF."
            );
        }

        // 2. Taille maximale
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ParseRagException(
                    HttpStatusCode.valueOf(413),
                    "FILE_TOO_LARGE",
                    "File size %.1f MB exceeds the 50 MB limit."
                            .formatted(file.getSize() / (1024.0 * 1024.0))
            );
        }

        // 3. Type MIME
        String contentType = file.getContentType();
        if (contentType == null || !contentType.equalsIgnoreCase(PDF_CONTENT_TYPE)) {
            throw new ParseRagException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "INVALID_FILE_FORMAT",
                    "Only PDF files are supported. Received: " + contentType
            );
        }

        // 4. Magic bytes (%PDF) — protège contre les fichiers renommés en .pdf
        try {
            byte[] header = file.getBytes();
            if (header.length < 4 || !new String(header, 0, 4).equals(PDF_MAGIC_BYTES)) {
                throw new ParseRagException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_FILE_FORMAT",
                        "File does not have a valid PDF signature."
                );
            }
        } catch (IOException e) {
            throw new ParseRagException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL_ERROR",
                    "Could not read the uploaded file."
            );
        }
    }

    /**
     * Détecte la langue depuis les métadonnées PDF.
     * Sprint 2 remplacera ceci par une vraie détection NLP sur le texte extrait.
     */
    private String detectLanguage(PDDocumentCatalog catalog) {
        String lang = catalog.getLanguage();
        if (lang != null && !lang.isBlank()) {
            // "fr-FR" → "fr"
            return lang.split("-")[0].toLowerCase();
        }
        return "unknown";
    }

    /** Génère un identifiant unique de document : "doc_8f3a2b1c" */
    private String generateDocumentId() {
        return "doc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}