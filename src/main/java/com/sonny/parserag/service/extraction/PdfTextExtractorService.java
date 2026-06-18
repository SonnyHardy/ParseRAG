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
import org.apache.pdfbox.text.TextPosition;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class PdfTextExtractorService {

    /** Résolution de l'histogramme X en points PDF (1 = max, 1 bin = 1pt). */
    private static final int HISTOGRAM_RES_PT = 1;

    /**
     * Largeur minimale (pt) d'un vide horizontal pour le considérer comme une gouttière.
     * Gouttière typique ACM/IEEE : ~12pt — 8pt offre une marge de sécurité.
     * TODO: au cas où il serait nécessaire d'ajuster ultérieurement.
     */
    private static final float MIN_GUTTER_WIDTH_PT = 8f;

    /** Zone X (ratio de la largeur de page) où chercher les gouttières. Exclut marges + numéros de ligne. */
    private static final float X_SEARCH_START_RATIO = 0.15f;
    private static final float X_SEARCH_END_RATIO   = 0.85f;

    /** Zone Y (ratio de la hauteur) prise en compte pour l'histogramme. Exclut headers/footers. */
    private static final float Y_FILTER_TOP_RATIO    = 0.15f;
    private static final float Y_FILTER_BOTTOM_RATIO = 0.85f;

    /**
     * Tolérance verticale (pt) pour considérer deux fragments sur la même ligne.
     * TODO: pour une implémentation future (à affiner avec des fontes plus grandes).
     */
    private static final float SAME_LINE_TOLERANCE_PT = 1f;

    /** Largeur estimée d'un caractère espace (pt), utilisée pour reconstituer les écarts visuels. */
    private static final float SPACE_WIDTH_PT = 5f;
    private static final int   MAX_GAP_SPACES = 6;

    // ── Retrait des numéros de ligne de marge (copies de relecture/soumission) ──────────
    /** Un candidat numéro de ligne : entier court isolé. */
    private static final Pattern LINE_NUMBER_PATTERN     = Pattern.compile("^\\d{1,4}$");
    /** Percentile robuste pour estimer le bord du corps (ignore quelques fragments aberrants en marge). */
    private static final float   BODY_EDGE_PERCENTILE    = 0.10f;
    /** Marge (pt) en deçà du bord du corps pour qu'un numérique soit considéré « en marge extérieure ». */
    private static final float   LINE_NUMBER_MARGIN_GAP_PT = 2f;
    /** Tolérance X (pt) pour regrouper les numériques d'une même colonne de marge. */
    private static final float   LINE_NUMBER_CLUSTER_X_TOL_PT = 6f;
    /** Nombre minimal de numéros pour considérer une colonne (anti-coïncidence). */
    private static final int     LINE_NUMBER_MIN_COUNT   = 15;
    /** Densité minimale : la colonne doit couvrir ≥ 30% des baselines de la page. */
    private static final float   LINE_NUMBER_MIN_DENSITY = 0.30f;
    /** Fraction minimale de paires (triées par Y) où la valeur croît : une numérotation augmente vers le bas. */
    private static final float   LINE_NUMBER_MONOTONIC_MIN = 0.70f;

    private final AppProperties appProperties;

    // =========================================================================
    // Public API
    // =========================================================================

    public ExtractedDocument extract(byte[] pdfBytes, Plan plan) {
        int maxPages = appProperties.getPageLimits().forPlan(plan);

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {

            int pageCount = document.getNumberOfPages();
            if (pageCount > maxPages) {
                throw new ParseRagException(
                        HttpStatus.valueOf(422),
                        "DOCUMENT_TOO_LONG",
                        "Document has %d pages. Maximum allowed for your plan (%s): %d"
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
            String detectedLanguage = (metadataLanguage != null && !metadataLanguage.isBlank())
                    ? metadataLanguage.split("-")[0].toLowerCase()
                    : detectLanguage(allText);

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

    // =========================================================================
    // Per-page extraction — single PDFBox parse, in-memory re-assembly
    // =========================================================================

    private List<ExtractedPage> extractPages(PDDocument document) throws IOException {
        int pageCount = document.getNumberOfPages();
        List<ExtractedPage> pages = new ArrayList<>(pageCount);

        FragmentCapturingStripper stripper = new FragmentCapturingStripper();

        for (int pageNum = 1; pageNum <= pageCount; pageNum++) {
            long pageStart = System.currentTimeMillis();
            PDPage pdPage  = document.getPage(pageNum - 1);

            String rawText = extractPageText(document, pdPage, pageNum, stripper);

            boolean hasImages      = pageHasImages(pdPage);
            boolean likelyHasTable = looksLikeTable(rawText);

            log.debug("Page {}/{} extracted in {}ms ({} chars). Images: {}, Table: {}",
                    pageNum, pageCount, System.currentTimeMillis() - pageStart,
                    rawText.length(), hasImages, likelyHasTable);

            pages.add(new ExtractedPage(pageNum, rawText, hasImages, likelyHasTable));
        }

        return pages;
    }

    /**
     * Extrait le texte d'une page en un seul parsing PDFBox, puis détecte les
     * colonnes via histogramme X et réassemble le texte fragment par fragment.
     *
     * <p><strong>Pipeline :</strong>
     * <ol>
     *   <li>Parse PDFBox unique via {@link FragmentCapturingStripper} → liste de
     *       {@link Fragment} (texte + plage X + Y baseline).</li>
     *   <li>Histogramme 1pt sur l'axe X (filtré par bande Y centrale) →
     *       {@code splits[]} = liste des coordonnées X séparant les colonnes.
     *       {@code splits.length + 1} = nombre de colonnes (1 si vide).</li>
     *   <li>Assemblage : par colonne (bucket par midpoint X) ou mono.</li>
     *   <li>Fallback tableau : si {@link #looksLikeTable} fire sur le résultat
     *       multi-colonnes, on ré-assemble en mono pour préserver l'ordre du tableau.</li>
     * </ol>
     *
     * <p><strong>Coût :</strong> 1 parse PDFBox / page, indépendamment du nombre
     * de colonnes. Le reste est en mémoire (O(n log n) pour le tri par colonne).
     */
    private String extractPageText(PDDocument doc, PDPage page, int pageNum,
                                   FragmentCapturingStripper stripper) throws IOException {

        List<Fragment> fragments = stripper.parsePage(doc, pageNum);
        if (fragments.isEmpty()) return "";

        float pageWidth  = page.getMediaBox().getWidth();
        float pageHeight = page.getMediaBox().getHeight();

        // Retire les colonnes de numéros de ligne en marge (copies de relecture) avant tout assemblage.
        if (appProperties.getExtraction().isStripLineNumbers()) {
            fragments = stripLineNumberColumns(fragments, pageHeight, pageNum);
            if (fragments.isEmpty()) return "";
        }

        float[] splits = detectColumnSplits(fragments, pageWidth, pageHeight);

        if (splits.length == 0) {
            return assembleAsSingleColumn(fragments);
        }

        String multiColumnText = assembleAsColumns(fragments, splits);

        // Fallback : sur les pages détectées comme tableaux, le découpage par colonnes
        // massacre la structure. On bascule en mono-colonne (tri Y → X global).
        if (looksLikeTable(multiColumnText)) {
            log.debug("Page {} — tabular layout detected, falling back to mono-column", pageNum);
            return assembleAsSingleColumn(fragments);
        }

        log.debug("Page {} — {} columns detected, splits at {}",
                pageNum, splits.length + 1, Arrays.toString(splits));
        return multiColumnText;
    }

    // =========================================================================
    // Column detection — histogram-based, supports N columns
    // =========================================================================

    /**
     * Détecte les coordonnées X qui séparent les colonnes en cherchant les vides
     * suffisamment larges dans un histogramme haute résolution des positions X.
     *
     * <p>Tous les vides ≥ {@link #MIN_GUTTER_WIDTH_PT} dans la zone de recherche
     * sont retenus. Aucun cas spécial pour 2 colonnes — l'algorithme gère
     * naturellement 1, 2, 3, N colonnes.
     *
     * @return tableau (potentiellement vide) des splits X, en ordre croissant.
     *         {@code splits.length + 1} = nombre de colonnes détectées.
     */
    private float[] detectColumnSplits(List<Fragment> fragments, float pageWidth, float pageHeight) {
        float yMin = pageHeight * Y_FILTER_TOP_RATIO;
        float yMax = pageHeight * Y_FILTER_BOTTOM_RATIO;

        int bins = (int) Math.ceil(pageWidth / HISTOGRAM_RES_PT);
        int[] hist = new int[bins];

        for (Fragment f : fragments) {
            // Filtre Y : exclut headers/footers qui combleraient artificiellement les gouttières
            if (f.y() < yMin || f.y() > yMax) continue;
            int b0 = Math.max(0, (int) (f.xStart() / HISTOGRAM_RES_PT));
            int b1 = Math.min(bins - 1, (int) (f.xEnd() / HISTOGRAM_RES_PT));
            for (int b = b0; b <= b1; b++) hist[b]++;
        }

        int searchStart  = (int) (bins * X_SEARCH_START_RATIO);
        int searchEnd    = Math.min(bins, (int) (bins * X_SEARCH_END_RATIO));
        int minGutterBin = (int) Math.ceil(MIN_GUTTER_WIDTH_PT / HISTOGRAM_RES_PT);

        List<Float> splits = new ArrayList<>();
        int gapStart = -1;

        for (int i = searchStart; i < searchEnd; i++) {
            boolean empty = hist[i] == 0;
            if (empty) {
                if (gapStart == -1) gapStart = i;
            } else if (gapStart != -1) {
                if (i - gapStart >= minGutterBin) {
                    splits.add(((gapStart + i) / 2f) * HISTOGRAM_RES_PT);
                }
                gapStart = -1;
            }
        }
        // Vide qui se prolonge jusqu'à la fin de la zone de recherche
        if (gapStart != -1 && (searchEnd - gapStart) >= minGutterBin) {
            splits.add(((gapStart + searchEnd) / 2f) * HISTOGRAM_RES_PT);
        }

        float[] result = new float[splits.size()];
        for (int i = 0; i < splits.size(); i++) result[i] = splits.get(i);
        return result;
    }

    // =========================================================================
    // Line-number column removal — copies de relecture / soumission
    // =========================================================================

    /**
     * Retire les fragments formant une (ou deux) colonne(s) de numéros de ligne en marge.
     * <p>
     * Approche en 3 garde-fous pour ne jamais toucher du contenu (numéro de section
     * « 2 Expected contributions », colonne numérique d'un tableau, numéro d'équation) :
     * <ol>
     *   <li><strong>marge extérieure</strong> : le numérique doit être strictement à gauche du
     *       bord gauche du corps (ou à droite du bord droit), bord estimé par percentile robuste
     *       des fragments non-numériques ;</li>
     *   <li><strong>densité</strong> : la colonne couvre ≥ {@link #LINE_NUMBER_MIN_DENSITY} des
     *       baselines de la page (et ≥ {@link #LINE_NUMBER_MIN_COUNT}) ;</li>
     *   <li><strong>monotonie</strong> : triés par Y, les nombres croissent (une numérotation
     *       augmente vers le bas) sur ≥ {@link #LINE_NUMBER_MONOTONIC_MIN} des paires.</li>
     * </ol>
     * Si aucune colonne ne valide ces trois critères, la liste est renvoyée inchangée.
     */
    private List<Fragment> stripLineNumberColumns(List<Fragment> fragments, float pageHeight, int pageNum) {
        float yMin = pageHeight * Y_FILTER_TOP_RATIO;
        float yMax = pageHeight * Y_FILTER_BOTTOM_RATIO;

        List<Fragment> numerics  = new ArrayList<>();
        List<Float>    bodyStart = new ArrayList<>();
        List<Float>    bodyEnd   = new ArrayList<>();
        for (Fragment f : fragments) {
            if (LINE_NUMBER_PATTERN.matcher(f.text()).matches()) {
                numerics.add(f);
            } else if (f.y() >= yMin && f.y() <= yMax) {
                bodyStart.add(f.xStart());
                bodyEnd.add(f.xEnd());
            }
        }
        if (numerics.size() < LINE_NUMBER_MIN_COUNT || bodyStart.isEmpty()) return fragments;

        float bodyLeftEdge  = percentile(bodyStart, BODY_EDGE_PERCENTILE);
        float bodyRightEdge = percentile(bodyEnd, 1f - BODY_EDGE_PERCENTILE);

        int baselineCount = distinctBaselineCount(fragments);
        int minCount = Math.max(LINE_NUMBER_MIN_COUNT, Math.round(LINE_NUMBER_MIN_DENSITY * baselineCount));

        List<Fragment> leftCands  = new ArrayList<>();
        List<Fragment> rightCands = new ArrayList<>();
        for (Fragment f : numerics) {
            if (f.xEnd() <= bodyLeftEdge - LINE_NUMBER_MARGIN_GAP_PT)        leftCands.add(f);
            else if (f.xStart() >= bodyRightEdge + LINE_NUMBER_MARGIN_GAP_PT) rightCands.add(f);
        }

        Set<Fragment> toRemove = Collections.newSetFromMap(new IdentityHashMap<>());
        collectLineNumberClusters(leftCands, minCount, toRemove);
        collectLineNumberClusters(rightCands, minCount, toRemove);

        if (toRemove.isEmpty()) return fragments;

        List<Fragment> kept = new ArrayList<>(fragments.size());
        for (Fragment f : fragments) if (!toRemove.contains(f)) kept.add(f);
        log.debug("Page {} — stripped {} line-number fragments ({} kept)", pageNum, toRemove.size(), kept.size());
        return kept;
    }

    /**
     * Regroupe les candidats par colonne (X proche) et marque pour suppression ceux dont la
     * colonne satisfait densité + monotonie.
     */
    private void collectLineNumberClusters(List<Fragment> candidates, int minCount, Set<Fragment> toRemove) {
        if (candidates.size() < minCount) return;

        List<Fragment> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(Fragment::xStart));

        List<Fragment> cluster = new ArrayList<>();
        float anchorX = sorted.getFirst().xStart();
        for (Fragment f : sorted) {
            if (f.xStart() - anchorX <= LINE_NUMBER_CLUSTER_X_TOL_PT) {
                cluster.add(f);
            } else {
                evaluateCluster(cluster, minCount, toRemove);
                cluster = new ArrayList<>();
                cluster.add(f);
                anchorX = f.xStart();
            }
        }
        evaluateCluster(cluster, minCount, toRemove);
    }

    /** Une colonne valide (assez dense + numérotation croissante vers le bas) est marquée pour retrait. */
    private void evaluateCluster(List<Fragment> cluster, int minCount, Set<Fragment> toRemove) {
        if (cluster.size() < minCount) return;

        List<Fragment> byY = new ArrayList<>(cluster);
        byY.sort(Comparator.comparingDouble(Fragment::y));
        int increasing = 0;
        for (int i = 1; i < byY.size(); i++) {
            if (parseIntSafe(byY.get(i).text()) >= parseIntSafe(byY.get(i - 1).text())) increasing++;
        }
        double monotonicFraction = (double) increasing / (byY.size() - 1);
        if (monotonicFraction >= LINE_NUMBER_MONOTONIC_MIN) {
            toRemove.addAll(cluster);
        }
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return Integer.MAX_VALUE; }
    }

    /** Valeur au percentile {@code p} (0..1) d'une liste de flottants. */
    private float percentile(List<Float> values, float p) {
        List<Float> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int idx = Math.clamp(Math.round(p * (sorted.size() - 1)), 0, sorted.size() - 1);
        return sorted.get(idx);
    }

    /** Nombre de baselines distinctes (à {@link #SAME_LINE_TOLERANCE_PT} près). */
    private int distinctBaselineCount(List<Fragment> fragments) {
        List<Float> ys = new ArrayList<>(fragments.size());
        for (Fragment f : fragments) ys.add(f.y());
        Collections.sort(ys);
        int count = 0;
        float last = Float.NEGATIVE_INFINITY;
        for (float y : ys) {
            if (y - last > SAME_LINE_TOLERANCE_PT) { count++; last = y; }
        }
        return count;
    }

    // =========================================================================
    // Assembly
    // =========================================================================

    /**
     * Assemble les fragments en mode mono-colonne : tri (Y, X), regroupement par
     * baseline avec espacement reconstitué proportionnellement à l'écart X réel
     * entre fragments d'une même ligne.
     */
    private String assembleAsSingleColumn(List<Fragment> fragments) {
        if (fragments.isEmpty()) return "";

        List<Fragment> sorted = new ArrayList<>(fragments);
        sorted.sort(Comparator.comparingDouble(Fragment::y)
                              .thenComparingDouble(Fragment::xStart));

        StringBuilder out = new StringBuilder(8192);
        Fragment prev = null;

        for (Fragment f : sorted) {
            if (prev == null) {
                out.append(f.text());
            } else if (Math.abs(f.y() - prev.y()) <= SAME_LINE_TOLERANCE_PT) {
                appendInterFragmentSpacing(out, prev, f);
                out.append(f.text());
            } else {
                out.append('\n').append(f.text());
            }
            prev = f;
        }
        return out.toString().strip();
    }

    /**
     * Assemble les fragments par colonne : bucket selon le midpoint X relatif aux
     * splits, puis chaque colonne est assemblée en mono et concaténée avec un
     * saut de ligne. Préserve l'ordre de lecture gauche → droite.
     */
    private String assembleAsColumns(List<Fragment> fragments, float[] splits) {
        int numCols = splits.length + 1;
        List<List<Fragment>> buckets = new ArrayList<>(numCols);
        for (int i = 0; i < numCols; i++) buckets.add(new ArrayList<>());

        for (Fragment f : fragments) {
            float midX = (f.xStart() + f.xEnd()) / 2f;
            int col = 0;
            while (col < splits.length && midX > splits[col]) col++;
            buckets.get(col).add(f);
        }

        StringBuilder out = new StringBuilder(8192);
        for (List<Fragment> bucket : buckets) {
            String colText = assembleAsSingleColumn(bucket);
            if (colText.isBlank()) continue;
            if (!out.isEmpty()) out.append('\n');
            out.append(colText);
        }
        return out.toString();
    }

    /**
     * Reconstitue l'écart visuel entre deux fragments d'une même ligne.
     * Sans cette reconstitution, on perdrait toute trace des espacements larges
     * (typiques des tableaux), rendant {@link #looksLikeTable} inopérant.
     */
    private void appendInterFragmentSpacing(StringBuilder out, Fragment prev, Fragment curr) {
        float gap = curr.xStart() - prev.xEnd();
        if (gap <= SPACE_WIDTH_PT) {
            out.append(' ');
        } else {
            int spaces = Math.clamp(Math.round(gap / SPACE_WIDTH_PT), 1, MAX_GAP_SPACES);
            out.repeat(" ", spaces);
        }
    }

    // =========================================================================
    // Internal stripper — captures fragments in a single page parse
    // =========================================================================

    /** Run de texte capturé : contenu + plage X + Y baseline. */
    private record Fragment(String text, float xStart, float xEnd, float y) {}

    /**
     * {@link PDFTextStripper} qui capture chaque text run sous forme de
     * {@link Fragment} sans rien écrire sur la sortie standard. Permet une
     * unique passe PDFBox par page ; le réassemblage se fait ensuite
     * intégralement en mémoire à partir des positions capturées.
     */
    private static final class FragmentCapturingStripper extends PDFTextStripper {

        private final List<Fragment> buffer = new ArrayList<>(2048);

        FragmentCapturingStripper() {
            super();
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (text == null || text.isEmpty() || positions == null || positions.isEmpty()) {
                return;
            }
            TextPosition first = positions.getFirst();
            TextPosition last  = positions.getLast();

            float xStart = first.getXDirAdj();
            float xEnd   = last.getXDirAdj() + last.getWidthDirAdj();
            float y      = first.getYDirAdj();

            buffer.add(new Fragment(text, xStart, xEnd, y));
        }

        List<Fragment> parsePage(PDDocument doc, int pageNum) throws IOException {
            buffer.clear();
            setStartPage(pageNum);
            setEndPage(pageNum);
            // Le Writer est ignoré : writeString est overridé pour ne pas y écrire.
            super.writeText(doc, new StringWriter());
            return new ArrayList<>(buffer);
        }
    }

    // =========================================================================
    // Helpers (inchangés sur le fond)
    // =========================================================================

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

    // Les tableaux seront traités plus tard lors de l'issue #9.
    private boolean looksLikeTable(String text) {
        return false;
        /*if (text == null || text.isBlank()) return false;
        long tabLines = text.lines()
                .filter(line -> line.contains("\t") || line.matches(".*\\s{3,}.*\\s{3,}.*"))
                .count();
        return tabLines >= 2;*/
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
