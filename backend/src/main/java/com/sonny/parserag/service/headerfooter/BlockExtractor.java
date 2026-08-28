package com.sonny.parserag.service.headerfooter;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Extrait les blocs d'un PDF : <strong>1 bloc = 1 ligne visuelle</strong> (fragments
 * d'une même baseline), regroupés <strong>par colonne</strong>.
 * <p>
 * Le découpage en colonnes réplique celui de {@code PdfTextExtractorService} (histogramme
 * X, gouttière ≥ 8 pt) pour qu'un bloc header/footer corresponde à une ligne réelle du
 * {@code rawText} — condition nécessaire pour que le strip aval fonctionne en multi-colonnes.
 */
@Component
class BlockExtractor {

    private static final int   HISTOGRAM_RES_PT      = 1;
    private static final float MIN_GUTTER_WIDTH_PT   = 8f;
    private static final float X_SEARCH_START_RATIO  = 0.15f;
    private static final float X_SEARCH_END_RATIO    = 0.85f;
    private static final float Y_FILTER_TOP_RATIO    = 0.15f;
    private static final float Y_FILTER_BOTTOM_RATIO = 0.85f;

    List<TextBlock> extract(byte[] pdfBytes, int pageCount, double sameLineTolerancePt) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            Stripper stripper = new Stripper();
            List<TextBlock> all = new ArrayList<>();
            int pages = Math.min(pageCount, document.getNumberOfPages());
            for (int p = 1; p <= pages; p++) {
                all.addAll(stripper.parsePage(document, p, sameLineTolerancePt));
            }
            return all;
        }
    }

    private record Fragment(String text, float xStart, float xEnd, float yTop, float yBottom) {}

    private static final class Stripper extends PDFTextStripper {

        private final List<Fragment> buffer = new ArrayList<>(2048);

        Stripper() throws IOException {
            super();
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (text == null || text.isBlank() || positions == null || positions.isEmpty()) return;
            TextPosition first = positions.getFirst();
            TextPosition last  = positions.getLast();
            float xStart = first.getXDirAdj();
            float xEnd   = last.getXDirAdj() + last.getWidthDirAdj();
            float yTop   = first.getYDirAdj() - first.getHeightDir();
            float yBot   = first.getYDirAdj();
            buffer.add(new Fragment(text.strip(), xStart, xEnd, yTop, yBot));
        }

        List<TextBlock> parsePage(PDDocument doc, int pageNum, double sameLineTolerancePt) throws IOException {
            buffer.clear();
            setStartPage(pageNum);
            setEndPage(pageNum);
            super.writeText(doc, new StringWriter());
            if (buffer.isEmpty()) return List.of();

            PDPage page  = doc.getPage(pageNum - 1);
            float width  = page.getMediaBox().getWidth();
            float height = page.getMediaBox().getHeight();

            float[] splits = detectColumnSplits(buffer, width, height);
            List<TextBlock> blocks = new ArrayList<>();
            for (List<Fragment> column : partitionByColumn(buffer, splits)) {
                groupByBaseline(column, pageNum, width, height, sameLineTolerancePt, blocks);
            }
            return blocks;
        }

        /** Histogramme X (bande Y centrale) → coordonnées des gouttières séparant les colonnes. */
        private static float[] detectColumnSplits(List<Fragment> fragments, float pageWidth, float pageHeight) {
            int bins = (int) Math.ceil(pageWidth / HISTOGRAM_RES_PT);
            if (bins <= 0) return new float[0];

            float yMin = pageHeight * Y_FILTER_TOP_RATIO;
            float yMax = pageHeight * Y_FILTER_BOTTOM_RATIO;

            int[] hist = new int[bins];
            for (Fragment f : fragments) {
                if (f.yBottom() < yMin || f.yBottom() > yMax) continue; // exclut headers/footers
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
                if (hist[i] == 0) {
                    if (gapStart == -1) gapStart = i;
                } else if (gapStart != -1) {
                    if (i - gapStart >= minGutterBin) splits.add(((gapStart + i) / 2f) * HISTOGRAM_RES_PT);
                    gapStart = -1;
                }
            }
            if (gapStart != -1 && (searchEnd - gapStart) >= minGutterBin) {
                splits.add(((gapStart + searchEnd) / 2f) * HISTOGRAM_RES_PT);
            }

            float[] result = new float[splits.size()];
            for (int i = 0; i < splits.size(); i++) result[i] = splits.get(i);
            return result;
        }

        private static List<List<Fragment>> partitionByColumn(List<Fragment> fragments, float[] splits) {
            int numCols = splits.length + 1;
            List<List<Fragment>> buckets = new ArrayList<>(numCols);
            for (int i = 0; i < numCols; i++) buckets.add(new ArrayList<>());
            for (Fragment f : fragments) {
                float midX = (f.xStart() + f.xEnd()) / 2f;
                int col = 0;
                while (col < splits.length && midX > splits[col]) col++;
                buckets.get(col).add(f);
            }
            return buckets;
        }

        /** Groupe les fragments d'une colonne en blocs same-baseline et les ajoute à {@code out}. */
        private static void groupByBaseline(List<Fragment> fragments, int pageNum,
                                            float pageWidth, float pageHeight,
                                            double sameLineTolerancePt, List<TextBlock> out) {
            if (fragments.isEmpty()) return;

            List<Fragment> sorted = new ArrayList<>(fragments);
            sorted.sort(Comparator.comparingDouble(Fragment::yTop)
                                  .thenComparingDouble(Fragment::xStart));

            List<List<Fragment>> groups = new ArrayList<>();
            List<Fragment> current = new ArrayList<>();
            current.add(sorted.getFirst());

            for (int i = 1; i < sorted.size(); i++) {
                Fragment prev = sorted.get(i - 1);
                Fragment f    = sorted.get(i);
                if (Math.abs(f.yTop() - prev.yTop()) <= sameLineTolerancePt) {
                    current.add(f);
                } else {
                    groups.add(current);
                    current = new ArrayList<>();
                    current.add(f);
                }
            }
            groups.add(current);

            for (List<Fragment> g : groups) {
                float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE;
                float x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
                StringBuilder sb = new StringBuilder();
                List<Fragment> rowSorted = new ArrayList<>(g);
                rowSorted.sort(Comparator.comparingDouble(Fragment::xStart));
                for (int i = 0; i < rowSorted.size(); i++) {
                    Fragment f = rowSorted.get(i);
                    x0 = Math.min(x0, f.xStart());
                    y0 = Math.min(y0, f.yTop());
                    x1 = Math.max(x1, f.xEnd());
                    y1 = Math.max(y1, f.yBottom());
                    if (i > 0) sb.append(' ');
                    sb.append(f.text());
                }
                out.add(new TextBlock(pageNum, x0, y0, x1, y1, sb.toString(), pageWidth, pageHeight));
            }
        }
    }
}
