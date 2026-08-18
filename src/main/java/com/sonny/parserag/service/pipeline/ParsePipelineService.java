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
import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.observability.ParseRagMetrics;
import com.sonny.parserag.observability.ParseRagMetrics.Outcome;
import com.sonny.parserag.observability.ParseRagMetrics.Stage;
import com.sonny.parserag.service.extraction.PdfTextExtractorService;
import com.sonny.parserag.service.extraction.ScannedPageDetector;
import com.sonny.parserag.service.extraction.TableExtractorService;
import com.sonny.parserag.service.extraction.TableRegionDetector;
import com.sonny.parserag.service.extraction.TableTextStripper;
import com.sonny.parserag.service.fallback.ScannedDocumentFallbackService;
import com.sonny.parserag.service.fallback.ScannedDocumentFallbackService.ScannedExtraction;
import com.sonny.parserag.service.fallback.VisionBudget;
import com.sonny.parserag.service.headerfooter.HeaderFooterCleaningService;
import com.sonny.parserag.service.processing.ChunkingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private static final String OCTET_STREAM_CONTENT_TYPE = "application/octet-stream";
    private static final String PDF_MAGIC_BYTES  = "%PDF";
    private static final long   MAX_FILE_SIZE    = 50L * 1024 * 1024; // 50 MB

    private final AppProperties appProperties;
    private final PdfTextExtractorService pdfTextExtractorService;
    private final HeaderFooterCleaningService headerFooterCleaningService;
    private final ChunkingService chunkingService;
    private final TableRegionDetector tableRegionDetector;
    private final TableExtractorService tableExtractorService;
    private final TableTextStripper tableTextStripper;
    private final ScannedPageDetector scannedPageDetector;
    private final ScannedDocumentFallbackService scannedDocumentFallbackService;
    private final ParseRagMetrics metrics;

    /**
     * Point d'entrée du pipeline. Enveloppe {@link #runPipeline} pour relever l'issue du parse
     * (issue #38) : le compteur {@code parserag.parse.total} est incrémenté sur <em>tous</em> les
     * chemins, succès comme échec, sinon le taux d'erreur par plan serait aveugle aux échecs.
     * L'exception est toujours relancée telle quelle — l'instrumentation n'altère aucun comportement.
     */
    public ParseResponse process(MultipartFile file, ApiKey apiKey) {
        Plan plan = apiKey != null ? apiKey.getPlan() : Plan.FREE;
        long startTime = System.currentTimeMillis();

        log.info("Pipeline start — file: '{}', size: {} bytes, plan: {}",
                file.getOriginalFilename(), file.getSize(), plan);

        try {
            ParseResponse response = runPipeline(file, plan);
            metrics.parseCompleted(plan, Outcome.SUCCESS, null, elapsed(startTime));
            return response;
        } catch (ParseRagException e) {
            metrics.parseCompleted(plan, Outcome.FAILURE, e.getErrorCode(), elapsed(startTime));
            throw e;
        } catch (RuntimeException e) {
            // Échec non métier : un code stable vaut mieux que le nom de la classe, qui ferait
            // varier la cardinalité du tag au gré des refactors.
            metrics.parseCompleted(plan, Outcome.FAILURE, "INTERNAL_ERROR", elapsed(startTime));
            throw e;
        }
    }

    private ParseResponse runPipeline(MultipartFile file, Plan plan) {
        long startTime = System.currentTimeMillis();

        byte[] bytes = readBytes(file);
        validateFile(file, bytes);

        ExtractedDocument extracted = metrics.stage(Stage.EXTRACT,
                () -> pdfTextExtractorService.extract(bytes, plan));
        ExtractedDocument cleaned = metrics.stage(Stage.CLEAN,
                () -> headerFooterCleaningService.clean(bytes, extracted));

        // Pages scannées / image-only : routées vers le fallback vision plein-page (issue #11).
        Set<Integer> scannedPages = scannedPageDetector.scannedPages(cleaned);
        metrics.scannedPagesDetected(scannedPages.size());
        // Budget vision partagé pour tout le document (tableaux + pages scannées) : un seul cap.
        VisionBudget visionBudget = new VisionBudget(appProperties.getVision().getMaxPagesPerDocument());

        // Détection des régions partagée : extraction structurée + excision du texte (anti-doublon).
        List<TableRegion> tableRegions = tableRegionDetector.detect(bytes);
        metrics.tablesDetected(tableRegions.size());

        List<TableResult> tables = new ArrayList<>(metrics.stage(Stage.TABLES,
                () -> tableExtractorService.extract(bytes, cleaned, tableRegions, visionBudget)));
        ExtractedDocument doc = tableTextStripper.strip(bytes, cleaned, tableRegions);

        List<Chunk> textChunks = metrics.stage(Stage.CHUNK, () -> chunkingService.chunk(doc));

        if (!scannedPages.isEmpty()) {
            // Écarter les éventuels chunks natifs des pages scannées (défensif), puis ajouter le fallback.
            textChunks = new ArrayList<>(textChunks.stream()
                    .filter(c -> !scannedPages.contains(c.page()))
                    .toList());
            ScannedExtraction scanned = metrics.stage(Stage.SCANNED,
                    () -> scannedDocumentFallbackService.process(bytes, doc, scannedPages, visionBudget));
            textChunks.addAll(scanned.textChunks());
            tables.addAll(scanned.tables());
        }

        // Cap vision atteint : des pages/tableaux sont partis en revue manuelle faute de budget.
        // Relevé ici, une fois par document — les deux consommateurs partagent le même compteur,
        // les instrumenter séparément compterait deux fois le même épuisement.
        if (visionBudget.max() > 0 && !visionBudget.hasRemaining()) {
            metrics.visionBudgetExhausted();
        }

        List<Chunk> chunks = assembleChunks(doc.documentId(), textChunks, tables);
        recordDocumentMetrics(plan, doc, bytes.length, chunks);

        long processingMs = System.currentTimeMillis() - startTime;
        log.info("Pipeline done — docId: {}, pages: {}, lang: {}, time: {}ms",
                doc.documentId(), doc.pageCount(), doc.detectedLanguage(), processingMs);

        return ParseResponse.ok(doc.documentId(), doc.pageCount(), doc.detectedLanguage(), processingMs, chunks);
    }

    /** Volumétrie du document et qualité de sa sortie (issue #38). */
    private void recordDocumentMetrics(Plan plan, ExtractedDocument doc, int bytes, List<Chunk> chunks) {
        int manualReview = (int) chunks.stream().filter(Chunk::manualReviewNeeded).count();
        metrics.documentParsed(plan, doc.pageCount(), bytes, chunks.size(), manualReview);
        for (Chunk c : chunks) metrics.chunkConfidence(c.confidence());
    }

    private static Duration elapsed(long startMillis) {
        return Duration.ofMillis(System.currentTimeMillis() - startMillis);
    }

    /**
     * Fusionne les chunks de texte et les tableaux en une seule liste ordonnée par page
     * (tri stable : le texte d'une page précède ses tableaux), puis ré-attribue des IDs
     * séquentiels. Le ré-adressage couvre aussi les chunks à id provisoire {@code null} issus du
     * fallback vision plein-page.
     */
    private List<Chunk> assembleChunks(String docId, List<Chunk> textChunks, List<TableResult> tables) {
        List<Chunk> merged = new ArrayList<>(textChunks.size() + tables.size());
        merged.addAll(textChunks);
        for (TableResult t : tables) merged.add(toTableChunk(t));
        merged.sort(Comparator.comparingInt(Chunk::page));

        List<Chunk> out = new ArrayList<>(merged.size());
        int i = 0;
        for (Chunk c : merged) {
            String id = "chunk_%s_%03d".formatted(docId, i++);
            out.add(new Chunk(id, c.text(), c.type(), c.page(), c.charStart(), c.charEnd(),
                    c.confidence(), c.fallbackUsed(), c.manualReviewNeeded(), c.tableJson()));
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
                t.confidence(), t.fallbackUsed(), false, tableJson);
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
        if (contentType == null || !(contentType.equalsIgnoreCase(PDF_CONTENT_TYPE)
                || contentType.equalsIgnoreCase(OCTET_STREAM_CONTENT_TYPE))) {
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
