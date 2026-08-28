package com.sonny.parserag.service.extraction;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.model.domain.Chunk;
import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.service.processing.ChunkingService;
import com.sonny.parserag.service.processing.ConfidenceCalculatorService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ordre de lecture sur le vrai BERT (arxiv-1810.04805, 2 colonnes), après la détection par bandes
 * (issue #31). Hors-ligne : extracteur + chunking purs, sans Spring ni datasource.
 *
 * <p><strong>Ces tests assertaient l'inverse avant #31</strong> : la page 1 entrelaçait ses colonnes
 * (bandeau titre traversant la gouttière → aucune colonne détectée → tri Y global) et émettait des
 * lignes suspectes. C'était le bug, pas le contrat. Depuis la détection par bandes, le titre forme
 * sa propre bande mono et le corps est assemblé colonne par colonne : plus aucune ligne suspecte.
 *
 * <p>La mécanique de détection elle-même (#30) reste couverte par
 * {@link SuspectLineDetectionTest}, qui l'exerce sur une séquence fabriquée — elle ne dépend donc
 * plus d'un défaut d'extraction pour être testée.
 */
class PdfTextExtractorReadingOrderTest {

    private static final AppProperties PROPS = props();
    private final PdfTextExtractorService extractor = new PdfTextExtractorService(PROPS, new PageGeometryAnalyzer());
    private final ChunkingService chunking = new ChunkingService(PROPS, new ConfidenceCalculatorService(PROPS));

    private static AppProperties props() {
        AppProperties props = new AppProperties();
        props.getExtraction().setStripLineNumbers(true);
        props.getConfidence().setMaxAnomalyRate(0.2);        // palier 1
        props.getConfidence().setPenaltyFloor(0.3);          // palier 1
        props.getConfidence().setMaxSuspectLineRate(0.3);    // palier 3
        props.getConfidence().setReadingOrderFloor(0.3);     // palier 3
        props.getConfidence().setManualReviewThreshold(0.4); // palier 3
        props.getChunking().setMaxChunkSize(2000);
        props.getChunking().setOverlap(100);
        props.getChunking().setMinChunkSize(30);
        props.getPageLimits().setMaxPagesFree(100);
        props.getPageLimits().setMaxPagesStarter(100);
        props.getPageLimits().setMaxPagesPro(100);
        props.getPageLimits().setMaxPagesScale(100);
        return props;
    }

    private byte[] load(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/sample-pdfs/pdfs/" + name)) {
            assertNotNull(in, "PDF introuvable : " + name);
            return in.readAllBytes();
        }
    }

    /** Critère d'acceptation de #31 : plus aucune page de BERT n'entrelace ses colonnes. */
    @Test
    void bert_noPageEmitsSuspectLinesAnymore() throws IOException {
        ExtractedDocument doc = extractor.extract(load("arxiv-1810.04805-bert-2col.pdf"), Plan.PRO);

        doc.pages().forEach(p -> assertEquals(0, p.reorderSuspectLines().size(),
                "page " + p.pageNumber() + " ne devrait plus émettre de ligne suspecte"));
    }

    /**
     * La page 1 portait le symptôme le plus visible : l'abstract cousu ligne à ligne avec la colonne
     * de droite. On vérifie sur le texte lui-même, pas seulement sur le compteur.
     */
    @Test
    void bert_page1_abstractReadsContiguously() throws IOException {
        ExtractedDocument doc = extractor.extract(load("arxiv-1810.04805-bert-2col.pdf"), Plan.PRO);
        String page1 = doc.pages().getFirst().rawText();

        // Fragment de la colonne GAUCHE qui, avant #31, était coupé par du texte de la colonne droite.
        assertTrue(page1.contains("There are two existing strategies for apply-"),
                "le début de la colonne gauche doit être présent");
        int leftIdx = page1.indexOf("There are two existing strategies for apply-");
        String following = page1.substring(leftIdx, Math.min(leftIdx + 120, page1.length()));
        assertFalse(following.contains("Abstract"),
                "la suite immédiate ne doit plus venir de l'autre colonne, or on y trouve : " + following);
    }

    /** Les chunks de la page 1 ne doivent plus être pénalisés ni marqués pour revue. */
    @Test
    void bert_page1_chunksNoLongerFlaggedForManualReview() throws IOException {
        ExtractedDocument doc = extractor.extract(load("arxiv-1810.04805-bert-2col.pdf"), Plan.PRO);
        List<Chunk> chunks = chunking.chunk(doc);

        assertFalse(chunks.stream().filter(c -> c.page() == 1).anyMatch(Chunk::manualReviewNeeded),
                "aucun chunk de la page 1 ne devrait rester marqué manual_review");

        // Le câblage manual_review ↔ seuil de confidence reste vrai (contrat de #30).
        double threshold = PROPS.getConfidence().getManualReviewThreshold();
        assertTrue(chunks.stream().allMatch(c -> c.manualReviewNeeded() == (c.confidence() < threshold)),
                "manual_review doit refléter exactement confidence < seuil");
    }
}
