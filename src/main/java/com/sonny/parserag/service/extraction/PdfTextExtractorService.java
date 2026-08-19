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
    /** En deçà de ce nombre de lignes, la page est trop courte pour juger son ordre de lecture. */
    private static final int   READING_ORDER_MIN_LINES = 5;
    /** Longueur minimale d'une ligne suspecte retenue (évite les faux appariements sur des lignes très courtes). */
    private static final int   MIN_SUSPECT_LINE_LEN    = 5;
    /**
     * Tolérance (pt) de regroupement des X de début de ligne en « ancres ». Large à dessein : le
     * bord d'une colonne n'est pas parfaitement régulier (chasse du premier glyphe, lignes
     * légèrement indentées). Mesuré : à 5 pt, l'ancre de la colonne droite de resnet p5 se
     * fragmentait en trois (297, 305, 309), chacune passant sous le seuil de représentativité.
     */
    private static final float ANCHOR_TOLERANCE_PT = 20f;
    /** Écart minimal (ratio de la largeur) entre deux ancres pour qu'elles puissent être deux colonnes. */
    private static final float MIN_ANCHOR_SEPARATION_RATIO = 0.15f;
    /** Part minimale des lignes portée par chacune des deux ancres retenues. */
    private static final float MIN_ANCHOR_SHARE = 0.20f;
    /** Nombre minimal de bascules gauche↔droite : c'est la répétition qui signe l'entrelacement. */
    private static final int   MIN_ALTERNATIONS = 4;

    /**
     * Nombre maximal de ré-assemblages tentés quand l'ordre de lecture produit se révèle entrelacé
     * (issue #31). Borné par construction : une boucle « jusqu'à ce que ce soit propre » ne
     * terminerait pas sur une page pathologique. Une tentative coûte ~0,2 ms (le PDF n'est pas
     * relu, seuls l'histogramme et le tri sont rejoués sur des fragments déjà en mémoire).
     */
    private static final int   MAX_REASSEMBLY_ATTEMPTS = 3;

    // ── Seconde signature : colonnes fusionnées dans une même ligne ──────────────────────
    /**
     * Écart interne (pt) à partir duquel un blanc dans une ligne cesse d'être une espace mot. Une
     * espace vaut ~5 pt, une gouttière ~12 pt et plus.
     */
    private static final float MERGED_COLUMN_MIN_GAP_PT = 12f;
    /**
     * Part des lignes devant présenter le blanc à la <em>même</em> abscisse. Élevée à dessein :
     * une gouttière traverse presque tout le corps de la page, alors qu'un tableau n'occupe qu'une
     * partie des lignes — c'est ce qui les distingue.
     */
    private static final float MERGED_COLUMN_MIN_SHARE = 0.50f;

    /** Texte reconstruit d'une page + ses lignes suspectes d'ordre de lecture (texte exact). */
    private record PageExtraction(String text, Set<String> reorderSuspectLines) {}
    /** Résultat d'assemblage : texte + X et texte de chaque ligne, dans l'ordre de lecture produit. */
    private record Assembled(String text, List<Float> lineStartX, List<String> lineTexts,
                             List<Float> lineGapX) {

        static Assembled empty() {
            return new Assembled("", List.of(), List.of(), List.of());
        }
    }

    /**
     * Verdict de l'oracle d'ordre de lecture : les lignes suspectes, et — quand un entrelacement est
     * reconnu — l'abscisse où passerait la gouttière. L'oracle ne fait pas que détecter, il
     * <em>localise</em> : c'est ce qui permet à la boucle de vérification de proposer un découpage
     * alternatif au lieu de tâtonner.
     */
    private record ReadingOrder(Set<String> suspectLines, float splitEstimate) {

        static final ReadingOrder CLEAN = new ReadingOrder(Set.of(), Float.NaN);

        boolean interleaved() {
            return !suspectLines.isEmpty();
        }

        boolean hasSplitEstimate() {
            return !Float.isNaN(splitEstimate);
        }
    }

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

        List<Band> bands = pageGeometryAnalyzer.columnBands(fragments, pageWidth, pageHeight);
        Assembled assembled = assembleAsBands(bands, pageNum);
        ReadingOrder verdict = analyseReadingOrder(assembled, pageWidth);

        if (log.isDebugEnabled() && bands.stream().anyMatch(b -> b.columns() > 1)) {
            log.debug("Page {} — {} band(s): {}", pageNum, bands.size(),
                    bands.stream().map(b -> b.columns() + "col/" + b.rows() + "r").toList());
        }

        // La géométrie propose, l'ordre de lecture dispose : si le texte produit porte la signature
        // d'un entrelacement, c'est que le découpage était faux — on réessaie plutôt que de livrer
        // deux colonnes cousues.
        if (verdict.interleaved()) {
            assembled = reassembleUntilReadable(fragments, assembled, verdict, pageWidth, pageHeight, pageNum);
            verdict = analyseReadingOrder(assembled, pageWidth);
        }

        return new PageExtraction(assembled.text(), verdict.suspectLines());
    }

    /**
     * Boucle de vérification (issue #31) : ré-assemble la page avec des découpages alternatifs tant
     * que l'ordre de lecture trahit un entrelacement, et garde le meilleur essai.
     *
     * <p><strong>Pourquoi une boucle plutôt qu'un meilleur seuil.</strong> Un seuil est calibré sur
     * un corpus et parie que le document suivant lui ressemble ; une vérification contrôle la sortie
     * réelle et ne présume rien de la mise en page. C'est ce qui rend l'extraction robuste à des
     * documents jamais vus.
     *
     * <p>Bornée à {@link #MAX_REASSEMBLY_ATTEMPTS} essais : jamais « jusqu'à ce que ce soit propre »,
     * qui ne terminerait pas sur une page pathologique. Si aucun essai n'est propre, on garde le
     * moins mauvais et ses lignes suspectes subsistent — l'échec reste <em>visible</em> en aval
     * (confiance abaissée, {@code manual_review}) plutôt que silencieusement livré.
     *
     * <p>Coût : le PDF n'est pas relu. Seuls l'histogramme et le tri sont rejoués sur des fragments
     * déjà en mémoire, soit ~0,2 ms par essai contre ~10 ms pour lire la page.
     */
    private Assembled reassembleUntilReadable(List<Fragment> fragments, Assembled nominal,
                                              ReadingOrder nominalVerdict, float pageWidth,
                                              float pageHeight, int pageNum) {
        Assembled best = nominal;
        int bestScore = nominalVerdict.suspectLines().size();

        for (float split : reassemblyCandidates(fragments, nominalVerdict, pageWidth, pageHeight)) {
            Assembled attempt = assembleAsColumns(fragments, new float[]{split});
            // Un découpage qui transforme la page en structure tabulaire a coupé un tableau en
            // deux plutôt que séparé deux colonnes : on l'écarte quoi qu'en dise le score.
            if (looksLikeTable(attempt.text())) continue;
            int score = analyseReadingOrder(attempt, pageWidth).suspectLines().size();
            if (score < bestScore) {
                best = attempt;
                bestScore = score;
            }
            if (bestScore == 0) break;
        }

        if (bestScore < nominalVerdict.suspectLines().size()) {
            log.debug("Page {} — reading order recovered by re-assembly ({} → {} suspect lines)",
                    pageNum, nominalVerdict.suspectLines().size(), bestScore);
        } else {
            log.debug("Page {} — still interleaved after {} attempt(s), {} suspect lines kept",
                    pageNum, MAX_REASSEMBLY_ATTEMPTS, bestScore);
        }
        return best;
    }

    /**
     * Découpages à essayer, par ordre de crédibilité décroissante : d'abord la gouttière déduite de
     * l'entrelacement lui-même (l'oracle sait où sont les deux bords de colonne), puis les
     * gouttières plausibles que la géométrie avait repérées mais écartées par prudence.
     */
    private List<Float> reassemblyCandidates(List<Fragment> fragments, ReadingOrder verdict,
                                             float pageWidth, float pageHeight) {
        List<Float> candidates = new ArrayList<>(MAX_REASSEMBLY_ATTEMPTS);
        if (verdict.hasSplitEstimate()) candidates.add(verdict.splitEstimate());

        for (float gutter : pageGeometryAnalyzer.candidateGutters(fragments, pageWidth, pageHeight)) {
            if (candidates.size() >= MAX_REASSEMBLY_ATTEMPTS) break;
            boolean alreadyCovered = candidates.stream()
                    .anyMatch(c -> Math.abs(c - gutter) < MIN_ANCHOR_SEPARATION_RATIO * pageWidth);
            if (!alreadyCovered) candidates.add(gutter);
        }
        return candidates;
    }

    /**
     * Assemble la page bande par bande, dans l'ordre de lecture (issue #31) : chaque bande est
     * rendue selon <em>sa</em> structure — mono-colonne pour un bandeau titre ou une légende pleine
     * largeur, colonne par colonne pour le corps — puis les bandes sont concaténées de haut en bas.
     */
    private Assembled assembleAsBands(List<Band> bands, int pageNum) {
        StringBuilder out = new StringBuilder(8192);
        List<Float>  lineStartX = new ArrayList<>();
        List<String> lineTexts  = new ArrayList<>();
        List<Float>  lineGapX   = new ArrayList<>();

        for (Band band : bands) {
            Assembled part = band.columns() == 1
                    ? assembleAsSingleColumn(band.fragments())
                    : assembleAsColumns(band.fragments(), band.splits());

            // Sur une bande tabulaire, le découpage en colonnes massacre la structure : les cellules
            // d'une même ligne partiraient dans des colonnes distinctes. On y revient au tri global.
            if (band.columns() > 1 && looksLikeTable(part.text())) {
                log.debug("Page {} — tabular band detected, falling back to mono-column", pageNum);
                part = assembleAsSingleColumn(band.fragments());
            }

            if (part.text().isBlank()) continue;
            if (!out.isEmpty()) out.append('\n');
            out.append(part.text());
            lineStartX.addAll(part.lineStartX());
            lineTexts.addAll(part.lineTexts());
            lineGapX.addAll(part.lineGapX());
        }
        return new Assembled(out.toString().strip(), lineStartX, lineTexts, lineGapX);
    }

    /**
     * Lignes en désordre d'ordre de lecture (palier 3), relevées sur le flux mono-colonne.
     *
     * <p>Ne retient que la <strong>signature</strong> de l'entrelacement : une alternance répétée
     * entre deux positions X <em>stables</em> et nettement séparées — la trace que laissent deux
     * colonnes cousues ligne à ligne. Un simple « saut arrière », critère de la première version,
     * ne suffit pas : un listing de code, une liste à puces, une équation centrée ou un tableau en
     * produisent constamment sans le moindre entrelacement. Mesuré sur le corpus, ce critère naïf
     * n'avait raison qu'une fois sur cinq (213 pages mono signalées à tort) ; la signature
     * bimodale porte la précision à 97 %.
     *
     * <p>Le texte exact des lignes est renvoyé pour permettre leur attribution par chunk au
     * chunking.
     */
    Set<String> computeSuspectLines(List<Float> lineStartX, List<String> lineTexts, float pageWidth) {
        return analyseAlternatingColumns(lineStartX, lineTexts, pageWidth).suspectLines();
    }

    /**
     * Verdict complet sur un assemblage : cherche les <strong>deux</strong> signatures
     * d'entrelacement, et renvoie dans les deux cas l'abscisse de gouttière déduite.
     *
     * <p>Deux colonnes cousues laissent deux traces distinctes selon que leurs lignes de base
     * coïncident ou non : si elles diffèrent, les débuts de ligne <em>alternent</em> ; si elles
     * coïncident, les colonnes fusionnent <em>dans</em> la même ligne, séparées par un large blanc
     * interne. Une seule des deux se voit à la fois, d'où l'examen en cascade.
     */
    private ReadingOrder analyseReadingOrder(Assembled assembled, float pageWidth) {
        ReadingOrder alternating = analyseAlternatingColumns(
                assembled.lineStartX(), assembled.lineTexts(), pageWidth);
        if (alternating.interleaved()) return alternating;
        return analyseMergedColumns(assembled.lineGapX(), assembled.lineTexts(), pageWidth);
    }

    /** Première signature : les débuts de ligne alternent entre deux bords de colonne. */
    private ReadingOrder analyseAlternatingColumns(List<Float> lineStartX, List<String> lineTexts,
                                                   float pageWidth) {
        int n = Math.min(lineStartX.size(), lineTexts.size());
        if (n < READING_ORDER_MIN_LINES) return ReadingOrder.CLEAN;

        List<float[]> anchors = anchors(lineStartX, n);
        if (anchors.size() < 2) return ReadingOrder.CLEAN;

        int required = (int) Math.ceil(MIN_ANCHOR_SHARE * n);
        float[] dominant = anchors.getFirst();
        if (dominant[1] < required) return ReadingOrder.CLEAN;

        // La seconde ancre est la plus fréquente *suffisamment éloignée* de la première — et non la
        // deuxième du classement : sur une colonne à deux niveaux d'indentation, les deux premières
        // seraient toutes deux à gauche et la vraie colonne opposée serait ignorée.
        float[] opposite = null;
        for (int i = 1; i < anchors.size(); i++) {
            float[] candidate = anchors.get(i);
            if (candidate[1] >= required
                    && Math.abs(candidate[0] - dominant[0]) >= MIN_ANCHOR_SEPARATION_RATIO * pageWidth) {
                opposite = candidate;
                break;
            }
        }
        if (opposite == null) return ReadingOrder.CLEAN;

        float left  = Math.min(dominant[0], opposite[0]);
        float right = Math.max(dominant[0], opposite[0]);

        // Côté de chaque ligne : 0 = ancre gauche, 1 = ancre droite, -1 = ni l'une ni l'autre.
        int[] side = new int[n];
        int alternations = 0;
        int previous = -1;
        for (int i = 0; i < n; i++) {
            float x = lineStartX.get(i);
            side[i] = Math.abs(x - left) <= ANCHOR_TOLERANCE_PT ? 0
                    : Math.abs(x - right) <= ANCHOR_TOLERANCE_PT ? 1
                    : -1;
            if (side[i] >= 0) {
                if (previous >= 0 && previous != side[i]) alternations++;
                previous = side[i];
            }
        }
        // Un aller-retour isolé (une figure, un encadré) n'est pas un entrelacement : il en faut
        // la répétition.
        if (alternations < MIN_ALTERNATIONS) return ReadingOrder.CLEAN;

        Set<String> suspect = new HashSet<>();
        for (int i = 1; i < n; i++) {
            if (side[i] == 0 && side[i - 1] == 1) {          // retour à gauche après la colonne droite
                String line = lineTexts.get(i).strip();
                if (line.length() >= MIN_SUSPECT_LINE_LEN) suspect.add(line);
            }
        }
        // Le milieu des deux bords de colonne : il tombe forcément entre les deux colonnes, donc
        // sépare correctement leurs fragments par leur milieu.
        return new ReadingOrder(suspect, (left + right) / 2f);
    }

    /**
     * Seconde signature : les deux colonnes ont fusionné <em>dans</em> la même ligne. Quand leurs
     * lignes de base coïncident, l'assemblage mono les concatène au lieu de les alterner, laissant
     * un blanc large et récurrent à la position de la gouttière (le symptôme
     * « customiza-&nbsp;&nbsp;&nbsp;&nbsp;lack the necessary » de l'issue #31).
     *
     * <p>Un tableau produit lui aussi des blancs internes alignés : deux gardes l'en distinguent.
     * D'une part le blanc doit se retrouver sur au moins {@link #MERGED_COLUMN_MIN_SHARE} des
     * lignes — une gouttière traverse tout le corps, un tableau n'occupe qu'une partie de la page.
     * D'autre part le ré-assemblage qui suivra est rejeté s'il produit une structure tabulaire.
     */
    private ReadingOrder analyseMergedColumns(List<Float> lineGapX, List<String> lineTexts,
                                              float pageWidth) {
        int n = Math.min(lineGapX.size(), lineTexts.size());
        if (n < READING_ORDER_MIN_LINES) return ReadingOrder.CLEAN;

        List<Float> gaps = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (!Float.isNaN(lineGapX.get(i))) gaps.add(lineGapX.get(i));
        }
        if (gaps.size() < Math.ceil(MERGED_COLUMN_MIN_SHARE * n)) return ReadingOrder.CLEAN;

        // Les blancs doivent se regrouper autour d'une MÊME abscisse : c'est ce qui fait une
        // gouttière plutôt qu'une ponctuation de mise en page dispersée.
        List<float[]> clusters = anchors(gaps, gaps.size());
        float[] dominant = clusters.getFirst();
        if (dominant[1] < Math.ceil(MERGED_COLUMN_MIN_SHARE * n)) return ReadingOrder.CLEAN;

        Set<String> suspect = new HashSet<>();
        for (int i = 0; i < n; i++) {
            float gapX = lineGapX.get(i);
            if (!Float.isNaN(gapX) && Math.abs(gapX - dominant[0]) <= ANCHOR_TOLERANCE_PT) {
                String line = lineTexts.get(i).strip();
                if (line.length() >= MIN_SUSPECT_LINE_LEN) suspect.add(line);
            }
        }
        if (suspect.isEmpty()) return ReadingOrder.CLEAN;
        return new ReadingOrder(suspect, dominant[0]);
    }

    /**
     * Regroupe les X de début de ligne en ancres (position moyenne + effectif), triées par
     * effectif décroissant. Une ancre = un bord de colonne récurrent.
     */
    private List<float[]> anchors(List<Float> lineStartX, int n) {
        List<float[]> anchors = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            float x = lineStartX.get(i);
            float[] match = null;
            for (float[] anchor : anchors) {
                if (Math.abs(anchor[0] - x) <= ANCHOR_TOLERANCE_PT) { match = anchor; break; }
            }
            if (match == null) {
                anchors.add(new float[]{x, 1});
            } else {
                match[0] = (match[0] * match[1] + x) / (match[1] + 1);   // moyenne glissante
                match[1]++;
            }
        }
        anchors.sort((a, b) -> Float.compare(b[1], a[1]));
        return anchors;
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
        if (fragments.isEmpty()) return Assembled.empty();

        List<Fragment> sorted = new ArrayList<>(fragments);
        sorted.sort(Comparator.comparingDouble(Fragment::y)
                              .thenComparingDouble(Fragment::xStart));

        StringBuilder out = new StringBuilder(8192);
        List<Float>  lineStartX = new ArrayList<>();
        List<String> lineTexts  = new ArrayList<>();
        List<Float>  lineGapX   = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        Fragment prev = null;
        float widestGap = 0f;
        float widestGapX = Float.NaN;

        for (Fragment f : sorted) {
            if (prev == null) {
                out.append(f.text());
                line.append(f.text());
                lineStartX.add(f.xStart());
            } else if (Math.abs(f.y() - prev.y()) <= SAME_LINE_TOLERANCE_PT) {
                // Écart interne : deux colonnes fusionnées dans une même ligne laissent ici
                // un blanc bien plus large qu'une espace mot. On retient le plus large de la ligne.
                float gap = f.xStart() - prev.xEnd();
                if (gap > widestGap) {
                    widestGap = gap;
                    widestGapX = (prev.xEnd() + f.xStart()) / 2f;
                }
                String sp = interFragmentSpacing(prev, f);
                out.append(sp).append(f.text());
                line.append(sp).append(f.text());
            } else {
                lineTexts.add(line.toString());
                lineGapX.add(widestGap >= MERGED_COLUMN_MIN_GAP_PT ? widestGapX : Float.NaN);
                widestGap = 0f;
                widestGapX = Float.NaN;
                line.setLength(0);
                out.append('\n').append(f.text());
                line.append(f.text());
                lineStartX.add(f.xStart());
            }
            prev = f;
        }
        if (prev != null) {
            lineTexts.add(line.toString());
            lineGapX.add(widestGap >= MERGED_COLUMN_MIN_GAP_PT ? widestGapX : Float.NaN);
        }
        return new Assembled(out.toString().strip(), lineStartX, lineTexts, lineGapX);
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
        List<Float>  lineGapX   = new ArrayList<>();
        for (List<Fragment> bucket : buckets) {
            Assembled col = assembleAsSingleColumn(bucket);
            if (col.text().isBlank()) continue;
            if (!out.isEmpty()) out.append('\n');
            out.append(col.text());
            lineStartX.addAll(col.lineStartX());
            lineTexts.addAll(col.lineTexts());
            lineGapX.addAll(col.lineGapX());
        }
        return new Assembled(out.toString(), lineStartX, lineTexts, lineGapX);
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
