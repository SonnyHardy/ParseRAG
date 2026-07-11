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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
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

    /** Zone Y (ratio de la hauteur) prise en compte pour le retrait des numéros de ligne. */
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

    // ── Ordre de lecture : détection des lignes suspectes (issue #30, palier 3) ──────────
    /** Saut X arrière entre deux lignes (en ratio de la largeur de page) au-delà duquel une ligne est suspecte. */
    private static final float BACKWARD_JUMP_MIN_RATIO = 0.10f;
    /** En deçà de ce nombre de lignes, la page est trop courte pour juger son ordre de lecture. */
    private static final int   READING_ORDER_MIN_LINES = 5;
    /** Longueur minimale d'une ligne suspecte retenue (évite les faux appariements sur des lignes très courtes). */
    private static final int   MIN_SUSPECT_LINE_LEN    = 5;

    /** Texte reconstruit d'une page + ses lignes suspectes d'ordre de lecture (texte exact). */
    private record PageExtraction(String text, Set<String> reorderSuspectLines) {}
    /** Résultat d'assemblage : texte + X et texte de chaque ligne, dans l'ordre de lecture produit. */
    private record Assembled(String text, List<Float> lineStartX, List<String> lineTexts) {}

    private final AppProperties appProperties;
    private final PageGeometryAnalyzer pageGeometryAnalyzer;

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

            log.debug("Extraction complete — docId: {}, pages: {}, lang: {}", documentId, pageCount, detectedLanguage);

            return new ExtractedDocument(documentId, pageCount, detectedLanguage, title, pages);

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

        for (int pageNum = 1; pageNum <= pageCount; pageNum++) {
            long pageStart = System.currentTimeMillis();
            PDPage pdPage  = document.getPage(pageNum - 1);

            PageExtraction extraction = extractPageText(document, pdPage, pageNum);
            String rawText = extraction.text();

            boolean hasImages      = pageHasImages(pdPage);
            boolean likelyHasTable = pageLooksTabular(rawText);

            log.debug("Page {}/{} extracted in {}ms ({} chars). Images: {}, Table: {}, reorder-suspect lines: {}",
                    pageNum, pageCount, System.currentTimeMillis() - pageStart,
                    rawText.length(), hasImages, likelyHasTable, extraction.reorderSuspectLines().size());

            pages.add(new ExtractedPage(pageNum, rawText, hasImages, likelyHasTable,
                    extraction.reorderSuspectLines()));
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
    private PageExtraction extractPageText(PDDocument doc, PDPage page, int pageNum) throws IOException {

        List<Fragment> fragments = pageGeometryAnalyzer.fragments(doc, pageNum);
        if (fragments.isEmpty()) return new PageExtraction("", Set.of());

        float pageWidth  = page.getMediaBox().getWidth();
        float pageHeight = page.getMediaBox().getHeight();

        // Retire les colonnes de numéros de ligne en marge (copies de relecture) avant tout assemblage.
        if (appProperties.getExtraction().isStripLineNumbers()) {
            fragments = stripLineNumberColumns(fragments, pageHeight, pageNum);
            if (fragments.isEmpty()) return new PageExtraction("", Set.of());
        }

        float[] splits = pageGeometryAnalyzer.columnSplits(fragments, pageWidth, pageHeight);

        Assembled assembled;
        Set<String> suspectLines;
        if (splits.length == 0) {
            // Pas de gouttière détectée → assemblage mono-colonne. C'est ICI que deux colonnes mal
            // séparées s'entrelacent (cf. BERT p.1). On relève les lignes en désordre sur ce flux.
            assembled = assembleAsSingleColumn(fragments);
            suspectLines = computeSuspectLines(assembled.lineStartX(), assembled.lineTexts(), pageWidth);
        } else {
            assembled = assembleAsColumns(fragments, splits);

            // Fallback : sur les pages détectées comme tableaux, le découpage par colonnes
            // massacre la structure. On bascule en mono-colonne (tri Y → X global).
            if (looksLikeTable(assembled.text())) {
                log.debug("Page {} — tabular layout detected, falling back to mono-column", pageNum);
                assembled = assembleAsSingleColumn(fragments);
            } else {
                log.debug("Page {} — {} columns detected, splits at {}",
                        pageNum, splits.length + 1, Arrays.toString(splits));
            }
            // Colonnes correctement séparées (assemblées colonne par colonne) : on fait confiance à
            // l'ordre. Le bruit local d'une figure relève du palier 1, pas d'une anomalie de lecture.
            suspectLines = Set.of();
        }

        return new PageExtraction(assembled.text(), suspectLines);
    }

    /**
     * Lignes en désordre d'ordre de lecture (palier 3), détectées sur le flux mono-colonne. Sur la
     * suite des X de début de chaque ligne <em>dans l'ordre de lecture produit</em>, une ligne est
     * <strong>suspecte</strong> si elle démarre par un <em>saut X arrière anormal</em> (nettement à
     * gauche de la ligne précédente) : rare sur une vraie mono-colonne, systématique quand
     * l'assemblage a entrelacé deux colonnes (retour gauche après une ligne de la colonne droite).
     * Le texte exact de ces lignes est renvoyé pour permettre leur attribution par chunk au chunking.
     */
    private Set<String> computeSuspectLines(List<Float> lineStartX, List<String> lineTexts, float pageWidth) {
        int n = lineStartX.size();
        if (n < READING_ORDER_MIN_LINES) return Set.of();

        float threshold = pageWidth * BACKWARD_JUMP_MIN_RATIO;
        Set<String> suspect = new HashSet<>();
        for (int i = 1; i < n; i++) {
            if (lineStartX.get(i - 1) - lineStartX.get(i) > threshold) {
                String line = lineTexts.get(i).strip();
                if (line.length() >= MIN_SUSPECT_LINE_LEN) suspect.add(line);
            }
        }
        return suspect;
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
    private Assembled assembleAsSingleColumn(List<Fragment> fragments) {
        if (fragments.isEmpty()) return new Assembled("", List.of(), List.of());

        List<Fragment> sorted = new ArrayList<>(fragments);
        sorted.sort(Comparator.comparingDouble(Fragment::y)
                              .thenComparingDouble(Fragment::xStart));

        StringBuilder out = new StringBuilder(8192);
        List<Float>  lineStartX = new ArrayList<>();
        List<String> lineTexts  = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        Fragment prev = null;

        for (Fragment f : sorted) {
            if (prev == null) {
                out.append(f.text());
                line.append(f.text());
                lineStartX.add(f.xStart());
            } else if (Math.abs(f.y() - prev.y()) <= SAME_LINE_TOLERANCE_PT) {
                String sp = interFragmentSpacing(prev, f);
                out.append(sp).append(f.text());
                line.append(sp).append(f.text());
            } else {
                lineTexts.add(line.toString());
                line.setLength(0);
                out.append('\n').append(f.text());
                line.append(f.text());
                lineStartX.add(f.xStart());
            }
            prev = f;
        }
        if (prev != null) lineTexts.add(line.toString());
        return new Assembled(out.toString().strip(), lineStartX, lineTexts);
    }

    /**
     * Assemble les fragments par colonne : bucket selon le midpoint X relatif aux
     * splits, puis chaque colonne est assemblée en mono et concaténée avec un
     * saut de ligne. Préserve l'ordre de lecture gauche → droite.
     */
    private Assembled assembleAsColumns(List<Fragment> fragments, float[] splits) {
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
        List<Float>  lineStartX = new ArrayList<>();
        List<String> lineTexts  = new ArrayList<>();
        for (List<Fragment> bucket : buckets) {
            Assembled col = assembleAsSingleColumn(bucket);
            if (col.text().isBlank()) continue;
            if (!out.isEmpty()) out.append('\n');
            out.append(col.text());
            lineStartX.addAll(col.lineStartX());
            lineTexts.addAll(col.lineTexts());
        }
        return new Assembled(out.toString(), lineStartX, lineTexts);
    }

    /**
     * Reconstitue l'écart visuel entre deux fragments d'une même ligne.
     * Sans cette reconstitution, on perdrait toute trace des espacements larges
     * (typiques des tableaux), rendant {@link #looksLikeTable} inopérant.
     */
    private String interFragmentSpacing(Fragment prev, Fragment curr) {
        float gap = curr.xStart() - prev.xEnd();
        if (gap <= SPACE_WIDTH_PT) return " ";
        int spaces = Math.clamp(Math.round(gap / SPACE_WIDTH_PT), 1, MAX_GAP_SPACES);
        return " ".repeat(spaces);
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

    /**
     * Heuristique conservatrice pour le flag {@link ExtractedPage#likelyHasTable()} : une page
     * « tabulaire » a plusieurs lignes présentant au moins deux écarts larges (colonnes alignées
     * par espaces) ou des tabulations. Sert uniquement à gater le fallback Tabula sans bordures.
     */
    private boolean pageLooksTabular(String text) {
        if (text == null || text.isBlank()) return false;
        long tabularLines = text.lines()
                .filter(l -> l.contains("\t") || l.matches(".*\\S\\s{3,}\\S.*\\s{3,}\\S.*"))
                .count();
        return tabularLines >= 3;
    }

    // Conservé pour le fallback mono-colonne de l'assembleur (désactivé pour l'instant).
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
