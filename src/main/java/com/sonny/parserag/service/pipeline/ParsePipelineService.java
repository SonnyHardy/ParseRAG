package com.sonny.parserag.service.pipeline;

import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.exception.ParseRagException;
import com.sonny.parserag.model.domain.Chunk;
import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.model.response.ParseResponse;
import com.sonny.parserag.service.extraction.PdfTextExtractorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Orchestrateur central du pipeline de parsing ParseRAG.
 * <p>
 * Sprint 1 → validation fichier + métadonnées PDF
 * Sprint 2 → extraction texte via PdfTextExtractorService (ici)
 * Sprint 3 → extraction tableaux, fallback GPT-4o mini
 * Sprint 4 → tracking usage, rate limiting
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParsePipelineService {

    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final String PDF_MAGIC_BYTES  = "%PDF";
    private static final long   MAX_FILE_SIZE    = 50L * 1024 * 1024; // 50 MB

    private final PdfTextExtractorService pdfTextExtractorService;

    public ParseResponse process(MultipartFile file, ApiKey apiKey) {
        Plan plan = apiKey != null ? apiKey.getPlan() : Plan.FREE;
        long startTime = System.currentTimeMillis();
        log.info("Pipeline start — file: '{}', size: {} bytes, plan: {}",
                file.getOriginalFilename(), file.getSize(), plan);

        byte[] bytes = readBytes(file);
        validateFile(file, bytes);

        ExtractedDocument doc = pdfTextExtractorService.extract(bytes, plan);

        // Sprint 3 : chunks remplis par ChunkingService
        List<Chunk> chunks = Collections.emptyList();

        long processingMs = System.currentTimeMillis() - startTime;
        log.info("Pipeline done — docId: {}, pages: {}, lang: {}, time: {}ms",
                doc.documentId(), doc.pageCount(), doc.detectedLanguage(), processingMs);

        return ParseResponse.ok(doc.documentId(), doc.pageCount(), doc.detectedLanguage(), processingMs, chunks);
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ParseRagException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL_ERROR",
                    "Could not read the uploaded file."
            );
        }
    }

    private void validateFile(MultipartFile file, byte[] bytes) {
        if (file == null || file.isEmpty()) {
            throw new ParseRagException(
                    HttpStatus.BAD_REQUEST,
                    "MISSING_FILE",
                    "No file provided. Please attach a PDF."
            );
        }

        // Todo: Ajuster la taille de fichier maximale selon le plan
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ParseRagException(
                    HttpStatusCode.valueOf(413),
                    "FILE_TOO_LARGE",
                    "File size %.1f MB exceeds the 50 MB limit."
                            .formatted(file.getSize() / (1024.0 * 1024.0))
            );
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.equalsIgnoreCase(PDF_CONTENT_TYPE)) {
            throw new ParseRagException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "INVALID_FILE_FORMAT",
                    "Only PDF files are supported. Received: " + contentType
            );
        }

        if (bytes.length < 4 || !new String(bytes, 0, 4).equals(PDF_MAGIC_BYTES)) {
            throw new ParseRagException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_FILE_FORMAT",
                    "File does not have a valid PDF signature."
            );
        }
    }
}
