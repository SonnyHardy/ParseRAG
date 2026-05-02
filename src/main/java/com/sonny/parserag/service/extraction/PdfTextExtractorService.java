package com.sonny.parserag.service.extraction;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.exception.ParseRagException;
import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.model.domain.ExtractedPage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PdfTextExtractorService {

    private final AppProperties appProperties;

    public ExtractedDocument extract(byte[] pdfBytes, Plan plan) {
        int maxPages = appProperties.getPageLimits().forPlan(plan);

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {

            int pageCount = document.getNumberOfPages();
            if (pageCount > maxPages) {
                throw new ParseRagException(
                        HttpStatus.valueOf(422),
                        "DOCUMENT_TOO_LONG",
                        "Document has %d pages. Maximum allowed for your plan (%s): %d."
                                .formatted(pageCount, plan.name().toLowerCase(), maxPages)
                );
            }

            String title = document.getDocumentInformation().getTitle();
            List<ExtractedPage> pages = extractPages(document);

            String allText = pages.stream()
                    .map(ExtractedPage::rawText)
                    .filter(t -> t != null && !t.isBlank())
                    .reduce("", (a, b) -> a + " " + b);

            String metadataLanguage = document.getDocumentCatalog().getLanguage();
            String detectedLanguage = metadataLanguage != null ? metadataLanguage : detectLanguage(allText);

            String documentId = "doc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

            log.info("Extraction complete — docId: {}, pages: {}, lang: {}", documentId, pageCount, detectedLanguage);

            return new ExtractedDocument(documentId, pageCount, detectedLanguage, title, pages);

        } catch (ParseRagException e) {
            throw e;
        } catch (IOException e) {
            log.error("Failed to extract PDF content", e);
            throw new ParseRagException(
                    HttpStatus.BAD_REQUEST,
                    "PDF_UNREADABLE",
                    "The PDF could not be read. It may be corrupted or password-protected."
            );
        }
    }

    private List<ExtractedPage> extractPages(PDDocument document) throws IOException {
        int pageCount = document.getNumberOfPages();
        List<ExtractedPage> pages = new ArrayList<>(pageCount);

        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);

        for (int i = 1; i <= pageCount; i++) {
            long pageStart = System.currentTimeMillis();

            stripper.setStartPage(i);
            stripper.setEndPage(i);
            String rawText = stripper.getText(document).strip();

            // If text is sparse, try region-based extraction (multi-column layouts)
            if (rawText.length() < 50) {
                rawText = extractByArea(document, i).strip();
            }

            // Todo: For large documents (>= 1000 pages), use the iterator of getPages() instead.
            PDPage pdPage = document.getPage(i - 1);
            boolean hasImages = pageHasImages(pdPage);
            boolean likelyHasTable = looksLikeTable(rawText);

            log.debug("Page {}/{} extracted in {}ms ({} chars). Has images: {}, Looks like table: {}",
                    i, pageCount, System.currentTimeMillis() - pageStart, rawText.length(), hasImages, likelyHasTable);

            pages.add(new ExtractedPage(i, rawText, hasImages, likelyHasTable));
        }

        return pages;
    }

    /**
     * Détecte dynamiquement les colonnes de la page en sondant N bandes verticales fines,
     * puis extrait le texte de chaque colonne détectée dans l'ordre de lecture.
     * Fonctionne quels que soient le nombre de colonnes et leurs largeurs.
     */
    private String extractByArea(PDDocument document, int pageNumber) throws IOException {
        PDPage page       = document.getPage(pageNumber - 1);
        float pageWidth   = page.getMediaBox().getWidth();
        float pageHeight  = page.getMediaBox().getHeight();
        int   probeCount  = 20;
        float probeWidth  = pageWidth / probeCount;

        // Passe 1 : sonder chaque bande fine pour savoir où se trouve du texte
        PDFTextStripperByArea probe = new PDFTextStripperByArea();
        probe.setSortByPosition(true);
        for (int i = 0; i < probeCount; i++) {
            probe.addRegion("p" + i, new Rectangle2D.Float(i * probeWidth, 0, probeWidth, pageHeight));
        }
        probe.extractRegions(page);

        boolean[] hasText = new boolean[probeCount];
        for (int i = 0; i < probeCount; i++) {
            hasText[i] = !probe.getTextForRegion("p" + i).isBlank();
        }

        // Passe 2 : fusionner les bandes consécutives avec du texte en régions-colonnes
        PDFTextStripperByArea extractor = new PDFTextStripperByArea();
        extractor.setSortByPosition(true);
        int colCount = 0;
        int start    = -1;
        for (int i = 0; i <= probeCount; i++) {
            boolean inText = i < probeCount && hasText[i];
            if (inText && start == -1) {
                start = i;
            } else if (!inText && start != -1) {
                extractor.addRegion("col" + colCount++,
                        new Rectangle2D.Float(start * probeWidth, 0, (i - start) * probeWidth, pageHeight));
                start = -1;
            }
        }

        if (colCount == 0) {
            return "";
        }

        extractor.extractRegions(page);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < colCount; i++) {
            String colText = extractor.getTextForRegion("col" + i).strip();
            if (!colText.isEmpty()) result.append(colText).append("\n");
        }
        return result.toString();
    }

    private boolean pageHasImages(PDPage page) {
        PDResources resources = page.getResources();
        if (resources == null) return false;
        try {
            for (var name : resources.getXObjectNames()) {
                PDXObject xObject = resources.getXObject(name);
                if (xObject instanceof PDImageXObject) {
                    return true;
                }
            }
        } catch (IOException e) {
            log.warn("Could not inspect page resources for images", e);
        }
        return false;
    }

    // A line looks table-like when it has tabs or 3+ consecutive spaces at least twice
    private boolean looksLikeTable(String text) {
        if (text == null || text.isBlank()) return false;
        long tabLines = text.lines()
                .filter(line -> line.contains("\t") || line.matches(".*\\s{3,}.*\\s{3,}.*"))
                .count();
        return tabLines >= 2;
    }

    private String detectLanguage(String text) {
        if (text == null || text.isBlank()) return "unknown";

        String lower = text.toLowerCase(java.util.Locale.ROOT);

        Map<String, String[]> stopWords = Map.of(
                "fr", new String[]{"le", "la", "les", "de", "du", "des", "un", "une", "et", "en", "est", "que"},
                "en", new String[]{"the", "a", "an", "of", "to", "and", "in", "is", "it", "that", "for", "on"},
                "de", new String[]{"der", "die", "das", "und", "in", "ist", "ein", "eine", "zu", "den", "mit"},
                "es", new String[]{"el", "la", "los", "de", "en", "que", "un", "una", "es", "por", "con"}
        );

        String bestLang  = "unknown";
        long   bestScore = 0;

        for (Map.Entry<String, String[]> entry : stopWords.entrySet()) {
            long score = 0;
            for (String word : entry.getValue()) {
                int idx = 0;
                while ((idx = lower.indexOf(word, idx)) != -1) {
                    boolean before = idx == 0 || !Character.isLetter(lower.charAt(idx - 1));
                    boolean after  = (idx + word.length() >= lower.length())
                                  || !Character.isLetter(lower.charAt(idx + word.length()));
                    if (before && after) score++;
                    idx += word.length();
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestLang  = entry.getKey();
            }
        }

        return bestLang;
    }
}
