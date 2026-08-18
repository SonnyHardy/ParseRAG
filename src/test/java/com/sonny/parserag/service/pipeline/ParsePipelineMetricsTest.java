package com.sonny.parserag.service.pipeline;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.exception.ParseRagException;
import com.sonny.parserag.model.domain.Chunk;
import com.sonny.parserag.model.domain.ChunkType;
import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.observability.ParseRagMetrics;
import com.sonny.parserag.observability.TestMetrics;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Vérifie l'instrumentation du pipeline (issue #38) : chaque étage est chronométré et l'issue du
 * parse est comptée sur <em>tous</em> les chemins. Collaborateurs mockés — on teste le câblage de
 * l'observabilité, pas le parsing lui-même.
 */
class ParsePipelineMetricsTest {

    private static final ExtractedDocument DOC =
            new ExtractedDocument("doc_test", 3, "en", null, List.of());

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final ParseRagMetrics metrics = TestMetrics.metrics(meters);

    private final AppProperties props = new AppProperties();
    private final PdfTextExtractorService extractor = mock(PdfTextExtractorService.class);
    private final HeaderFooterCleaningService cleaner = mock(HeaderFooterCleaningService.class);
    private final ChunkingService chunking = mock(ChunkingService.class);
    private final TableRegionDetector regionDetector = mock(TableRegionDetector.class);
    private final TableExtractorService tableExtractor = mock(TableExtractorService.class);
    private final TableTextStripper stripper = mock(TableTextStripper.class);
    private final ScannedPageDetector scannedDetector = mock(ScannedPageDetector.class);
    private final ScannedDocumentFallbackService scannedFallback =
            mock(ScannedDocumentFallbackService.class);

    /** Chemin nominal : document natif de 3 pages, 2 chunks dont un en revue manuelle. */
    @BeforeEach
    void stubHappyPath() {
        props.getVision().setMaxPagesPerDocument(20);

        when(extractor.extract(any(), any())).thenReturn(DOC);
        when(cleaner.clean(any(), any())).thenReturn(DOC);
        when(scannedDetector.scannedPages(any())).thenReturn(Set.of());
        when(regionDetector.detect(any())).thenReturn(List.of());
        when(tableExtractor.extract(any(), any(), any(), any())).thenReturn(List.of());
        when(stripper.strip(any(), any(), any())).thenReturn(DOC);
        when(chunking.chunk(any())).thenReturn(List.of(
                new Chunk(null, "un chunk", ChunkType.PARAGRAPH, 1, 0, 8, 0.9, false, false, null),
                new Chunk(null, "revue", ChunkType.PARAGRAPH, 2, 0, 5, 0.3, false, true, null)));
    }

    private ParsePipelineService pipeline() {
        return new ParsePipelineService(props, extractor, cleaner, chunking, regionDetector,
                tableExtractor, stripper, scannedDetector, scannedFallback, metrics);
    }

    private static MultipartFile pdf() {
        return new MockMultipartFile("file", "doc.pdf", "application/pdf",
                "%PDF-1.4 contenu".getBytes());
    }

    private static ApiKey key(Plan plan) {
        ApiKey k = new ApiKey();
        k.setPlan(plan);
        return k;
    }

    // ── Chemin nominal ────────────────────────────────────────────────────────────────────

    @Test
    void successfulParseCountsOutcomeAndTimesEveryStage() {
        pipeline().process(pdf(), key(Plan.PRO));

        assertEquals(1, meters.get(ParseRagMetrics.PARSE_TOTAL)
                .tags("plan", "pro", "outcome", "success", "error_code", "none").counter().count());
        assertEquals(1, meters.get(ParseRagMetrics.PARSE_DURATION)
                .tags("plan", "pro", "outcome", "success").timer().count());

        // Sans page scannée, 4 étages tournent — l'étage scanned ne doit pas être chronométré à vide.
        for (String stage : List.of("extract", "clean", "tables", "chunk")) {
            assertEquals(1, meters.get(ParseRagMetrics.STAGE_DURATION)
                    .tag("stage", stage).timer().count(), "étage manquant : " + stage);
        }
        assertTrue(meters.find(ParseRagMetrics.STAGE_DURATION).tag("stage", "scanned")
                .timers().isEmpty(), "aucune page scannée → pas d'étage scanned");
    }

    @Test
    void successfulParseRecordsDocumentVolumetry() {
        pipeline().process(pdf(), key(Plan.PRO));

        assertEquals(3, meters.get(ParseRagMetrics.DOCUMENT_PAGES)
                .tag("plan", "pro").summary().totalAmount(), 1e-9);
        assertEquals(2, meters.get(ParseRagMetrics.CHUNKS_PRODUCED)
                .tag("plan", "pro").summary().totalAmount(), 1e-9);
        assertEquals(1, meters.get(ParseRagMetrics.CHUNKS_REVIEW).counter().count(),
                "le chunk en revue manuelle doit être compté");
        assertEquals(2, meters.get(ParseRagMetrics.CHUNK_CONFIDENCE).summary().count(),
                "une observation de confiance par chunk");
    }

    @Test
    void requestWithoutApiKeyIsCountedAsFree() {
        pipeline().process(pdf(), null);

        assertEquals(1, meters.get(ParseRagMetrics.PARSE_TOTAL).tag("plan", "free")
                .counter().count());
    }

    // ── Chemins d'échec ───────────────────────────────────────────────────────────────────

    /**
     * Le cas qui compte le plus : un parse qui échoue doit rester visible, avec son code d'erreur.
     * Un compteur posé uniquement sur le chemin nominal rendrait le taux d'erreur aveugle.
     */
    @Test
    void failedParseIsCountedWithItsErrorCode() {
        MultipartFile notAPdf = new MockMultipartFile("file", "doc.pdf", "application/pdf",
                "GIF89a".getBytes());

        assertThrows(ParseRagException.class, () -> pipeline().process(notAPdf, key(Plan.FREE)));

        assertEquals(1, meters.get(ParseRagMetrics.PARSE_TOTAL)
                .tags("plan", "free", "outcome", "failure", "error_code", "INVALID_FILE_FORMAT")
                .counter().count());
    }

    @Test
    void unexpectedFailureIsCountedUnderAStableCode() {
        // Une panne inattendue ne doit pas faire varier la cardinalité du tag au gré des refactors :
        // on veut un code fixe, pas le nom de la classe d'exception du jour.
        when(extractor.extract(any(), any())).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> pipeline().process(pdf(), key(Plan.SCALE)));

        assertEquals(1, meters.get(ParseRagMetrics.PARSE_TOTAL)
                .tags("plan", "scale", "outcome", "failure", "error_code", "INTERNAL_ERROR")
                .counter().count());
    }

    // ── Pages scannées & budget vision ────────────────────────────────────────────────────

    @Test
    void scannedStageIsTimedWhenScannedPagesExist() {
        when(scannedDetector.scannedPages(any())).thenReturn(Set.of(2));
        when(scannedFallback.process(any(), any(), any(), any())).thenReturn(ScannedExtraction.empty());

        pipeline().process(pdf(), key(Plan.FREE));

        assertEquals(1, meters.get(ParseRagMetrics.STAGE_DURATION)
                .tag("stage", "scanned").timer().count());
        assertEquals(1, meters.get(ParseRagMetrics.SCANNED_DETECTED).counter().count());
    }

    @Test
    void exhaustedVisionBudgetIsCountedOncePerDocument() {
        props.getVision().setMaxPagesPerDocument(1);
        // L'extraction de tableaux consomme l'unique jeton : le budget est épuisé en fin de parse.
        when(tableExtractor.extract(any(), any(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(3, VisionBudget.class).tryConsume();
            return List.of();
        });

        pipeline().process(pdf(), key(Plan.PRO));

        assertEquals(1, meters.get(ParseRagMetrics.VISION_BUDGET_OUT).counter().count());
    }

    @Test
    void unusedVisionBudgetReportsNothing() {
        pipeline().process(pdf(), key(Plan.PRO));

        assertTrue(meters.find(ParseRagMetrics.VISION_BUDGET_OUT).counters().isEmpty());
    }

    @Test
    void disabledVisionIsNotReportedAsExhaustedBudget() {
        // max = 0 veut dire « pas de vision du tout », pas « budget épuisé » — les confondre
        // ferait croire à une saturation permanente sur toute installation sans vision.
        props.getVision().setMaxPagesPerDocument(0);

        pipeline().process(pdf(), key(Plan.FREE));

        assertTrue(meters.find(ParseRagMetrics.VISION_BUDGET_OUT).counters().isEmpty());
    }
}
