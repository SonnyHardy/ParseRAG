package com.sonny.parserag.service.extraction;

import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.model.domain.ExtractedPage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Repère les pages <strong>scannées / image-only</strong> d'un document (issue #11) : une page
 * porteuse d'une image mais dépourvue de texte natif exploitable. Ces pages échappent au flux
 * d'extraction natif (texte/tableaux) et doivent être routées vers le fallback vision plein-page.
 *
 * <p>Détecteur <em>pur</em> : il statue sur les {@link ExtractedPage} déjà produites par
 * {@link PdfTextExtractorService} (flag {@code hasImages} + longueur du texte natif), sans re-parser
 * le PDF. Conservateur par construction — cf. {@link #MIN_NATIVE_TEXT_CHARS}.
 */
@Slf4j
@Service
public class ScannedPageDetector {

    /**
     * En deçà de ce nombre de caractères <em>non-blancs</em> de texte natif, une page
     * <strong>porteuse d'image</strong> est jugée scannée. Seuil bas et conservateur : une page de
     * texte normal en contient des centaines, là où une page scannée n'a au plus que des artefacts
     * d'OCR résiduels.
     *
     * <p>Garde-fou connu (à calibrer) : une page <em>figure pleine page</em> d'un PDF natif est aussi
     * « image + peu de texte » → faux positif possible. Raffinement par couverture surfacique de
     * l'image prévu si la calibration sur corpus le révèle nécessaire.
     */
    private static final int MIN_NATIVE_TEXT_CHARS = 80;

    /** Numéros (1-based) des pages scannées du document, dans l'ordre des pages. */
    public Set<Integer> scannedPages(ExtractedDocument doc) {
        Set<Integer> out = new LinkedHashSet<>();
        if (doc == null || doc.pages() == null) return out;
        for (ExtractedPage page : doc.pages()) {
            if (isScanned(page)) out.add(page.pageNumber());
        }
        if (!out.isEmpty()) {
            log.info("Scanned-page detection — docId: {}, scanned pages: {}", doc.documentId(), out);
        }
        return out;
    }

    /** Vrai si la page porte une image et n'a (quasi) pas de texte natif. */
    public boolean isScanned(ExtractedPage page) {
        return page != null
                && page.hasImages()
                && nonBlankCharCount(page.rawText()) < MIN_NATIVE_TEXT_CHARS;
    }

    private long nonBlankCharCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        return text.chars().filter(c -> !Character.isWhitespace(c)).count();
    }
}
