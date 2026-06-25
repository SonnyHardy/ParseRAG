package com.sonny.parserag.service.pipeline;

import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.exception.ParseRagException;
import com.sonny.parserag.model.domain.Chunk;
import com.sonny.parserag.model.domain.ChunkType;
import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.model.domain.TableRegion;
import com.sonny.parserag.model.domain.TableResult;
import com.sonny.parserag.model.response.ParseResponse;
import com.sonny.parserag.service.extraction.PdfTextExtractorService;
import com.sonny.parserag.service.extraction.TableExtractorService;
import com.sonny.parserag.service.extraction.TableRegionDetector;
import com.sonny.parserag.service.extraction.TableTextStripper;
import com.sonny.parserag.service.headerfooter.HeaderFooterCleaningService;
import com.sonny.parserag.service.processing.ChunkingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final HeaderFooterCleaningService headerFooterCleaningService;
    private final ChunkingService chunkingService;
    private final TableRegionDetector tableRegionDetector;
    private final TableExtractorService tableExtractorService;
    private final TableTextStripper tableTextStripper;

    public ParseResponse process(MultipartFile file, ApiKey apiKey) {
        Plan plan = apiKey != null ? apiKey.getPlan() : Plan.FREE;

        long startTime = System.currentTimeMillis();
        log.info("Pipeline start — file: '{}', size: {} bytes, plan: {}",
                file.getOriginalFilename(), file.getSize(), plan);

        byte[] bytes = readBytes(file);
        validateFile(file, bytes);

        ExtractedDocument doc = pdfTextExtractorService.extract(bytes, plan);
        doc = headerFooterCleaningService.clean(bytes, doc);

        // Détection des régions partagée : extraction structurée + excision du texte (anti-doublon).
        List<TableRegion> tableRegions = tableRegionDetector.detect(bytes);
        List<TableResult> tables = tableExtractorService.extract(bytes, doc, tableRegions);
        doc = tableTextStripper.strip(bytes, doc, tableRegions);

        List<Chunk> textChunks = chunkingService.chunk(doc);
        List<Chunk> chunks = assembleChunks(doc.documentId(), textChunks, tables);

        long processingMs = System.currentTimeMillis() - startTime;
        log.info("Pipeline done — docId: {}, pages: {}, lang: {}, time: {}ms",
                doc.documentId(), doc.pageCount(), doc.detectedLanguage(), processingMs);

        return ParseResponse.ok(doc.documentId(), doc.pageCount(), doc.detectedLanguage(), processingMs, chunks);
    }

    /**
     * Fusionne les chunks de texte et les tableaux en une seule liste ordonnée par page
     * (tri stable : le texte d'une page précède ses tableaux), puis ré-attribue des IDs
     * séquentiels. Si aucun tableau, la liste texte est renvoyée telle quelle.
     */
    private List<Chunk> assembleChunks(String docId, List<Chunk> textChunks, List<TableResult> tables) {
        if (tables.isEmpty()) return textChunks;

        List<Chunk> merged = new ArrayList<>(textChunks.size() + tables.size());
        merged.addAll(textChunks);
        for (TableResult t : tables) merged.add(toTableChunk(t));
        merged.sort(Comparator.comparingInt(Chunk::page));

        List<Chunk> out = new ArrayList<>(merged.size());
        int i = 0;
        for (Chunk c : merged) {
            String id = "chunk_%s_%03d".formatted(docId, i++);
            out.add(new Chunk(id, c.text(), c.type(), c.page(), c.charStart(), c.charEnd(),
                    c.confidence(), c.fallbackUsed(), c.tableJson()));
        }
        return out;
    }

    /** Mappe un tableau en chunk TABLE : table_json structuré + texte linéarisé (embeddable RAG). */
    private Chunk toTableChunk(TableResult t) {
        Map<String, Object> tableJson = new LinkedHashMap<>();
        if (t.caption() != null && !t.caption().isBlank()) tableJson.put("caption", t.caption());
        tableJson.put("headers", t.headers());
        tableJson.put("rows", t.rows());

        // id provisoire (null) — ré-attribué dans assembleChunks.
        return new Chunk(null, linearizeTable(t), ChunkType.TABLE, t.page(), 0, 0,
                t.confidence(), t.fallbackUsed(), tableJson);
    }

    private String linearizeTable(TableResult t) {
        StringBuilder sb = new StringBuilder();
        if (t.caption() != null && !t.caption().isBlank()) sb.append(t.caption()).append('\n');
        sb.append(String.join(" | ", t.headers()));
        for (List<String> row : t.rows()) sb.append('\n').append(String.join(" | ", row));
        return sb.toString();
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
