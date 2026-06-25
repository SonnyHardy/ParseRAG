package com.sonny.parserag.service.extraction;

import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.model.domain.ExtractedPage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste la logique pure de détection des pages scannées (image-only), sans PDF réel.
 */
class ScannedPageDetectorTest {

    private final ScannedPageDetector detector = new ScannedPageDetector();

    private static ExtractedPage page(int n, String text, boolean hasImages) {
        return new ExtractedPage(n, text, hasImages, false);
    }

    @Test
    void imageOnlyPage_isScanned() {
        assertTrue(detector.isScanned(page(1, "", true)), "image + texte vide");
        assertTrue(detector.isScanned(page(1, "   \n  \t ", true)), "image + que du blanc");
        assertTrue(detector.isScanned(page(1, "p. 12", true)), "image + résidu OCR court");
    }

    @Test
    void nativeTextPage_isNotScanned() {
        String prose = "Lorem ipsum dolor sit amet ".repeat(20);
        assertFalse(detector.isScanned(page(1, prose, false)), "texte natif, aucune image");
        assertFalse(detector.isScanned(page(1, prose, true)), "figure dans une page de texte abondant");
    }

    @Test
    void pageWithoutImage_isNeverScanned() {
        assertFalse(detector.isScanned(page(1, "", false)), "page blanche sans image ≠ scan");
    }

    @Test
    void thresholdBoundary() {
        assertTrue(detector.isScanned(page(1, "a".repeat(79), true)), "79 car. + image → scanné");
        assertFalse(detector.isScanned(page(1, "a".repeat(80), true)), "80 car. → assez de texte natif");
    }

    @Test
    void whitespaceIsNotCounted() {
        // 20 caractères réels noyés dans des espaces → toujours sous le seuil.
        assertTrue(detector.isScanned(page(1, "a b".repeat(10), true)));
    }

    @Test
    void collectsScannedPages() {
        ExtractedDocument doc = new ExtractedDocument("doc1", 4, "en", null, List.of(
                page(1, "native text ".repeat(20), false),  // texte
                page(2, "", true),                            // scan
                page(3, "x", true),                           // scan
                page(4, "more native text ".repeat(20), true) // image + texte abondant → natif
        ));
        assertEquals(Set.of(2, 3), detector.scannedPages(doc));
    }

    @Test
    void nullSafe() {
        assertTrue(detector.scannedPages(null).isEmpty());
        assertFalse(detector.isScanned(null));
    }
}
