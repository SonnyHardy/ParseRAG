package com.sonny.parserag.service.headerfooter;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.model.domain.ExtractedDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Orchestrateur du nettoyage header/footer en <strong>couches empilées</strong>.
 * <p>
 * Pipeline :
 * <ol>
 *   <li>extraction des blocs (1 passe PDFBox, column-aware) ;</li>
 *   <li>chaque {@link HeaderFooterDetector} (ordonné par {@code @Order}) renvoie les blocs
 *       qu'il juge header/footer ; les confirmations s'unionnent et sont transmises aux
 *       couches suivantes ;</li>
 *   <li>les lignes des blocs confirmés sont retirées du {@code rawText} de chaque page.</li>
 * </ol>
 * Couches livrées : géométrie+récurrence (primaire) → boilerplate (règles de contenu) →
 * DBSCAN (filet secondaire). Ajouter une couche = ajouter un {@code @Component} implémentant
 * {@link HeaderFooterDetector} ; aucun autre fichier à toucher.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeaderFooterCleaningService {

    private final AppProperties appProperties;
    private final BlockExtractor blockExtractor;
    private final List<HeaderFooterDetector> detectors; // injectés ordonnés (@Order)
    private final LineStripper lineStripper;

    public ExtractedDocument clean(byte[] pdfBytes, ExtractedDocument doc) {
        AppProperties.HeaderFooterCleaning cfg = appProperties.getHeaderFooterCleaning();
        if (!cfg.isEnabled() || doc == null || doc.pages() == null || doc.pages().isEmpty()) {
            return doc;
        }

        List<TextBlock> blocks;
        try {
            blocks = blockExtractor.extract(pdfBytes, doc.pageCount(), cfg.getBlockVerticalGapPt());
        } catch (IOException e) {
            log.warn("Header/footer cleaning skipped — block extraction failed for docId: {}",
                    doc.documentId(), e);
            return doc;
        }
        if (blocks.isEmpty()) {
            return doc;
        }

        Set<TextBlock> confirmed = new HashSet<>();
        for (HeaderFooterDetector detector : detectors) {
            HeaderFooterDetector.DetectionContext ctx = new HeaderFooterDetector.DetectionContext(
                    blocks, doc.pageCount(), doc.documentId(), cfg, confirmed);
            Set<TextBlock> found = detector.detect(ctx);
            int before = confirmed.size();
            confirmed.addAll(found);
            log.debug("HF layer '{}' — docId: {}, +{} new confirmations (total {})",
                    detector.name(), doc.documentId(), confirmed.size() - before, confirmed.size());
        }

        if (confirmed.isEmpty()) {
            log.info("Header/footer cleaning — docId: {}, nothing confirmed across {} blocks",
                    doc.documentId(), blocks.size());
            return doc;
        }

        LineStripper.Result result = lineStripper.strip(doc, confirmed);
        log.info("Header/footer cleaning complete — docId: {}, blocks confirmed: {}/{}, lines stripped: {}",
                doc.documentId(), confirmed.size(), blocks.size(), result.linesRemoved());

        return new ExtractedDocument(
                doc.documentId(),
                doc.pageCount(),
                doc.detectedLanguage(),
                doc.title(),
                result.pages());
    }
}
