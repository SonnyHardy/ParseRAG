package com.sonny.parserag.service.extraction;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Analyse géométrique bas niveau d'une page PDF, partagée entre l'extraction de
 * texte ({@link PdfTextExtractorService}) et la détection de régions de tableau
 * ({@link TableRegionDetector}).
 *
 * <p>Deux primitives :
 * <ol>
 *   <li>{@link #fragments(PDDocument, int)} : capture des {@link Fragment} (un text run =
 *       texte + plage X + Y baseline) en une unique passe PDFBox ;</li>
 *   <li>{@link #columnSplits(List, float, float)} : coordonnées X séparant les colonnes,
 *       via histogramme haute résolution des positions X.</li>
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

    /**
     * Capture chaque text run de la page sous forme de {@link Fragment}, en une unique
     * passe PDFBox. Le réassemblage éventuel se fait ensuite en mémoire.
     */
    public List<Fragment> fragments(PDDocument doc, int pageNum) throws IOException {
        return new FragmentCapturingStripper().parsePage(doc, pageNum);
    }

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
    public float[] columnSplits(List<Fragment> fragments, float pageWidth, float pageHeight) {
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
