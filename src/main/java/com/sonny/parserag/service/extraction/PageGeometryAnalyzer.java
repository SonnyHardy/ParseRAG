package com.sonny.parserag.service.extraction;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Analyse géométrique bas niveau d'une page PDF, partagée entre l'extraction de
 * texte ({@link PdfTextExtractorService}) et la détection de régions de tableau
 * ({@link TableRegionDetector}).
 *
 * <p>Deux primitives :
 * <ol>
 *   <li>{@link #fragments(PDDocument, int)} : capture des {@link Fragment} (un text run =
 *       texte + plage X + Y baseline) en une unique passe PDFBox ;</li>
 *   <li>{@link #columnBands(List, float, float)} : découpage de la page en bandes horizontales à
 *       structure de colonnes homogène (issue #31) ;</li>
 *   <li>{@link #columnSplits(List, float, float)} : raccourci vers les colonnes de la bande
 *       dominante, pour les appelants qui n'ont besoin que de la structure principale.</li>
 * </ol>
 *
 * <p>Garder cette logique au même endroit garantit qu'une région de tableau s'aligne
 * sur le <em>même</em> découpage colonne que le {@code rawText} produit par l'extracteur
 * (essentiel pour le multi-colonnes).
 */
@Slf4j
@Component
public class PageGeometryAnalyzer {

    /** Résolution de l'histogramme X en points PDF (1 = max, 1 bin = 1pt). */
    private static final int HISTOGRAM_RES_PT = 1;

    /**
     * Largeur minimale (pt) d'un vide horizontal pour le considérer comme une gouttière.
     * Gouttière typique ACM/IEEE : ~12pt — 8pt offre une marge de sécurité.
     */
    private static final float MIN_GUTTER_WIDTH_PT = 8f;

    /** Zone X (ratio de la largeur de page) où chercher les gouttières. Exclut marges + numéros de ligne. */
    private static final float X_SEARCH_START_RATIO = 0.15f;
    private static final float X_SEARCH_END_RATIO   = 0.85f;

    /** Zone Y (ratio de la hauteur) prise en compte pour l'histogramme. Exclut headers/footers. */
    private static final float Y_FILTER_TOP_RATIO    = 0.15f;
    private static final float Y_FILTER_BOTTOM_RATIO = 0.85f;

    // ── Détection par bandes (issue #31) ────────────────────────────────────────────────
    /**
     * Passe « candidats » : part des baselines qu'un bin de gouttière peut tolérer. Généreuse à
     * dessein — elle ne fait que <em>localiser</em> une gouttière possible ; la passe par bande et
     * les gardes ci-dessous décident si c'en est vraiment une.
     */
    private static final float CANDIDATE_TOLERANCE_RATIO = 0.10f;

    /**
     * Passe par bande : plus stricte, mais jamais nulle. Sur les vraies pages, une gouttière n'est
     * pas parfaitement vide — descendeurs, filets, résidus de figure la traversent (mesuré : resnet
     * p1 n'a jamais 8pt consécutifs strictement vides, alors que la gouttière existe bel et bien).
     */
    private static final float BAND_TOLERANCE_RATIO = 0.05f;

    /**
     * Part minimale des fragments de la bande de <strong>chaque</strong> côté d'un split. Sans cette
     * garde, une marge ou une indentation de puces passe pour une gouttière et découpe un document
     * mono-colonne (mesuré sur attention-1col et thinkpython avant #31).
     */
    private static final float MIN_SIDE_SHARE = 0.15f;

    /**
     * Largeur minimale d'une colonne (ratio de la page). Deux splits plus proches que cela décrivent
     * la <em>même</em> gouttière, coupée en deux par un mince résidu : on les fusionne.
     */
    private static final float MIN_COLUMN_WIDTH_RATIO = 0.12f;

    /** Sous ce nombre de baselines, une bande est trop courte pour qu'un découpage ait du sens. */
    private static final int MIN_BAND_ROWS = 3;

    /** Marge (pt) au-delà d'une gouttière candidate pour juger qu'un fragment la « traverse ». */
    private static final float CROSSING_MARGIN_PT = 2f;

    /**
     * Capture chaque text run de la page sous forme de {@link Fragment}, en une unique
     * passe PDFBox. Le réassemblage éventuel se fait ensuite en mémoire.
     */
    public List<Fragment> fragments(PDDocument doc, int pageNum) throws IOException {
        return new FragmentCapturingStripper().parsePage(doc, pageNum);
    }

    /**
     * Découpe la page en {@link Band bandes} horizontales à structure de colonnes homogène
     * (issue #31).
     *
     * <p><strong>Pourquoi des bandes.</strong> Chercher une gouttière vide sur toute la hauteur est
     * une condition binaire et fragile : un seul élément qui la traverse — bandeau titre, ligne
     * d'auteurs, tampon arXiv, légende de figure — annule la détection pour la page entière et fait
     * retomber l'assemblage en mono-colonne, ce qui entrelace les deux colonnes. Or ces éléments ne
     * sont pas des anomalies : ce sont les <em>frontières</em> naturelles entre zones de mise en
     * page. On s'en sert donc comme séparateurs au lieu de les subir.
     *
     * <p><strong>Deux passes.</strong> D'abord une passe tolérante sur toute la page pour
     * <em>localiser</em> les gouttières plausibles ; les fragments qui les traversent découpent
     * ensuite la page en bandes, et chaque bande est analysée pour elle-même. Une bande sans
     * gouttière est mono-colonne.
     *
     * <p>Tout raisonne au niveau {@link Fragment} et jamais sur des lignes regroupées par Y :
     * regrouper par Y fusionnerait les deux colonnes en une ligne pleine largeur, qui comblerait la
     * gouttière qu'on cherche précisément à trouver.
     *
     * @return bandes dans l'ordre de lecture (haut → bas) ; jamais vide si {@code fragments} ne
     *         l'est pas, et leur union contient exactement tous les fragments.
     */
    public List<Band> columnBands(List<Fragment> fragments, float pageWidth, float pageHeight) {
        if (fragments == null || fragments.isEmpty()) return List.of();

        // ── Passe A : où pourrait se trouver une gouttière ? ──
        List<Fragment> core = fragments.stream()
                .filter(f -> f.y() >= pageHeight * Y_FILTER_TOP_RATIO
                          && f.y() <= pageHeight * Y_FILTER_BOTTOM_RATIO)
                .toList();
        int coreRows = rows(core);
        float[] candidates = gutters(core, pageWidth, (int) Math.floor(CANDIDATE_TOLERANCE_RATIO * coreRows));

        if (candidates.length == 0) {
            return List.of(new Band(List.copyOf(fragments), new float[0]));
        }

        // ── Passe B : les fragments qui traversent une candidate délimitent les bandes ──
        Set<Integer> cutBaselines = new HashSet<>();
        for (Fragment f : fragments) {
            for (float gutter : candidates) {
                if (f.xStart() < gutter - CROSSING_MARGIN_PT && f.xEnd() > gutter + CROSSING_MARGIN_PT) {
                    cutBaselines.add(Math.round(f.y()));
                    break;
                }
            }
        }

        List<Fragment> sorted = new ArrayList<>(fragments);
        sorted.sort(Comparator.comparingDouble(Fragment::y));

        List<Band> bands = new ArrayList<>();
        List<Fragment> current = new ArrayList<>();
        List<Fragment> separator = new ArrayList<>();

        for (Fragment f : sorted) {
            if (cutBaselines.contains(Math.round(f.y()))) {
                if (!current.isEmpty()) {
                    bands.add(buildBand(current, pageWidth));
                    current = new ArrayList<>();
                }
                separator.add(f);          // la ligne traversante est mono-colonne par nature
            } else {
                if (!separator.isEmpty()) {
                    bands.add(new Band(List.copyOf(separator), new float[0]));
                    separator = new ArrayList<>();
                }
                current.add(f);
            }
        }
        if (!separator.isEmpty()) bands.add(new Band(List.copyOf(separator), new float[0]));
        if (!current.isEmpty())   bands.add(buildBand(current, pageWidth));

        return mergeAdjacentMonoBands(bands);
    }

    /**
     * Coordonnées X séparant les colonnes de la <strong>bande dominante</strong> de la page (celle
     * qui porte le plus de fragments), c'est-à-dire sa structure de colonnes principale.
     *
     * <p>Consommée par {@link TableRegionDetector} pour aligner ses régions sur le même découpage
     * que le texte : les deux doivent voir la page de la même façon, c'est la raison d'être de cette
     * classe partagée.
     *
     * @return tableau (potentiellement vide) des splits X, en ordre croissant.
     *         {@code splits.length + 1} = nombre de colonnes.
     */
    public float[] columnSplits(List<Fragment> fragments, float pageWidth, float pageHeight) {
        return columnBands(fragments, pageWidth, pageHeight).stream()
                .max(Comparator.comparingInt(b -> b.fragments().size()))
                .map(Band::splits)
                .orElseGet(() -> new float[0]);
    }

    // =========================================================================
    // Interne — géométrie des gouttières
    // =========================================================================

    private Band buildBand(List<Fragment> bandFragments, float pageWidth) {
        int bandRows = rows(bandFragments);
        float[] splits = bandRows >= MIN_BAND_ROWS
                ? mergeCloseSplits(
                        gutters(bandFragments, pageWidth, (int) Math.floor(BAND_TOLERANCE_RATIO * bandRows)),
                        pageWidth)
                : new float[0];
        return new Band(List.copyOf(bandFragments), splits);
    }

    /**
     * Vides horizontaux d'au moins {@link #MIN_GUTTER_WIDTH_PT} dont l'occupation ne dépasse pas
     * {@code maxOccupancy} fragments, et qui portent du texte substantiel des <em>deux</em> côtés.
     */
    private float[] gutters(List<Fragment> fragments, float pageWidth, int maxOccupancy) {
        int bins = (int) Math.ceil(pageWidth / HISTOGRAM_RES_PT);
        int[] hist = new int[bins];
        for (Fragment f : fragments) {
            int b0 = Math.max(0, (int) (f.xStart() / HISTOGRAM_RES_PT));
            int b1 = Math.min(bins - 1, (int) (f.xEnd() / HISTOGRAM_RES_PT));
            for (int b = b0; b <= b1; b++) hist[b]++;
        }

        int searchStart  = (int) (bins * X_SEARCH_START_RATIO);
        int searchEnd    = Math.min(bins, (int) (bins * X_SEARCH_END_RATIO));
        int minGutterBin = (int) Math.ceil(MIN_GUTTER_WIDTH_PT / HISTOGRAM_RES_PT);

        List<Float> splits = new ArrayList<>();
        int gapStart = -1;
        for (int i = searchStart; i <= searchEnd; i++) {
            boolean empty = i < searchEnd && hist[i] <= maxOccupancy;
            if (empty) {
                if (gapStart == -1) gapStart = i;
            } else if (gapStart != -1) {
                if (i - gapStart >= minGutterBin) {
                    float split = ((gapStart + i) / 2f) * HISTOGRAM_RES_PT;
                    if (hasTextOnBothSides(fragments, split)) splits.add(split);
                }
                gapStart = -1;
            }
        }

        float[] result = new float[splits.size()];
        for (int i = 0; i < splits.size(); i++) result[i] = splits.get(i);
        return result;
    }

    /** Un split n'est une gouttière que si chaque côté porte au moins {@link #MIN_SIDE_SHARE} du texte. */
    private boolean hasTextOnBothSides(List<Fragment> fragments, float split) {
        int left = 0;
        int right = 0;
        for (Fragment f : fragments) {
            if ((f.xStart() + f.xEnd()) / 2f < split) left++;
            else right++;
        }
        int required = (int) Math.ceil(MIN_SIDE_SHARE * fragments.size());
        return left >= required && right >= required;
    }

    /** Fusionne les splits trop rapprochés pour délimiter de vraies colonnes (même gouttière vue deux fois). */
    private float[] mergeCloseSplits(float[] splits, float pageWidth) {
        if (splits.length <= 1) return splits;

        float minWidth = MIN_COLUMN_WIDTH_RATIO * pageWidth;
        List<Float> merged = new ArrayList<>();
        float sum = splits[0];
        int count = 1;
        for (int i = 1; i < splits.length; i++) {
            if (splits[i] - (sum / count) < minWidth) {
                sum += splits[i];
                count++;
            } else {
                merged.add(sum / count);
                sum = splits[i];
                count = 1;
            }
        }
        merged.add(sum / count);

        float[] result = new float[merged.size()];
        for (int i = 0; i < merged.size(); i++) result[i] = merged.get(i);
        return result;
    }

    /** Deux bandes mono consécutives décrivent un même bandeau : les garder séparées n'apporte rien. */
    private List<Band> mergeAdjacentMonoBands(List<Band> bands) {
        List<Band> merged = new ArrayList<>(bands.size());
        for (Band band : bands) {
            if (!merged.isEmpty()) {
                Band previous = merged.getLast();
                if (previous.splits().length == 0 && band.splits().length == 0) {
                    List<Fragment> combined = new ArrayList<>(previous.fragments());
                    combined.addAll(band.fragments());
                    merged.set(merged.size() - 1, new Band(combined, new float[0]));
                    continue;
                }
            }
            merged.add(band);
        }
        return merged;
    }

    /** Nombre de baselines distinctes (au point près). */
    private static int rows(List<Fragment> fragments) {
        return (int) fragments.stream().mapToInt(f -> Math.round(f.y())).distinct().count();
    }

    /**
     * {@link PDFTextStripper} qui capture chaque text run sous forme de {@link Fragment}
     * sans rien écrire sur la sortie standard. Permet une unique passe PDFBox par page ;
     * le réassemblage se fait ensuite intégralement en mémoire à partir des positions.
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
}
