package com.sonny.parserag.service.extraction;

import com.sonny.parserag.model.domain.TableRegion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.stereotype.Service;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Localise les <strong>régions</strong> de tableau d'un PDF natif (issue #9), par deux ancres
 * complémentaires, et tague chacune via {@link TableRegion#bordered()} — la ligne de partage
 * du routage d'extraction aval.
 *
 * <ol>
 *   <li><strong>Ancre A — à bordures</strong> : {@link SpreadsheetExtractionAlgorithm} détecte
 *       les tableaux par leurs filets et fournit directement leur rectangle. Un filtre de densité
 *       de cellules ({@link #MIN_BORDERED_CELLS}) écarte les grilles de graphiques.</li>
 *   <li><strong>Ancre B — sans bordures</strong> : une légende « Table N » (ancrage strict)
 *       délimite, par balayage vertical des lignes <em>tabulaires</em> adjacentes dans sa colonne,
 *       l'enveloppe du tableau — le cas booktabs/LaTeX que Tabula ne voit pas. Les zones déjà
 *       couvertes par l'ancre A sont ignorées.</li>
 * </ol>
 *
 * <p>C'est un détecteur pur : il ne touche ni au pipeline, ni à l'extraction du contenu structuré.
 * Aucun tableau → liste vide, jamais d'exception (les erreurs par page sont loguées et ignorées).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableRegionDetector {

    /**
     * Ancrage strict (ancre B) : une ligne qui <em>commence</em> par « Table N » suivi d'un
     * séparateur de légende ({@code :} ou tiret) crée une région. Le séparateur obligatoire
     * écarte la prose (« Table 2 shows… », « …in Table 6. In this table… »).
     */
    private static final Pattern CAPTION_ANCHOR = Pattern.compile(
            "(?i)^\\s*(?:table|tableau|tab\\.)\\s*\\d+\\s*[:\\-–—]\\s+\\S.*$");
    /** Label permissif : « … Table N … » n'importe où, pour étiqueter une région à bordures. */
    private static final Pattern CAPTION_LABEL = Pattern.compile(
            "(?i).*\\b(?:table|tableau|tab\\.)\\s*\\d+\\b.*");
    /** Légende de figure : une grille à bordures proche d'un « Figure N » est un diagramme, pas un tableau. */
    private static final Pattern FIGURE_LABEL = Pattern.compile(
            "(?i).*\\b(?:figure|fig\\.)\\s*\\d+\\b.*");
    /** Un token est « numérique » s'il contient au moins un chiffre. */
    private static final Pattern HAS_DIGIT = Pattern.compile(".*\\d.*");

    /** Deux fragments d'une même ligne logique : écart Y ≤ tolérance. */
    private static final float LINE_Y_TOLERANCE_PT = 2f;
    /** Écart X minimal séparant deux cellules d'une ligne tabulaire. */
    private static final float CELL_GAP_PT = 10f;
    /** Une ligne « prose » (→ stop du balayage) : peu de cellules mais texte long et continu. */
    private static final int   PROSE_MIN_CHARS = 60;
    /** Ratio de tokens numériques au-delà duquel une ligne est jugée tabulaire. */
    private static final double NUMERIC_RATIO = 0.5;
    /** Nb minimal de lignes réellement tabulaires pour valider une région sans bordures. */
    private static final int   MIN_TABULAR_LINES = 2;
    /** Multiplicateur de l'interligne médian au-delà duquel on coupe (fin de tableau). */
    private static final float MAX_ROW_GAP_FACTOR = 2.5f;
    /** Garde-fou si l'interligne médian est indéterminé. */
    private static final float MAX_ROW_GAP_FALLBACK_PT = 28f;
    /** Le 1ᵉʳ saut légende→corps est souvent plus large (filet booktabs + interligne) : tolérance accrue. */
    private static final float FIRST_HOP_MIN_GAP_PT = 50f;
    /** Borne du balayage vertical autour d'une légende (anti-emballement). */
    private static final int   MAX_SCAN_LINES = 40;
    /** Distance Y max (pt) entre une boîte à bordures et sa légende candidate. */
    private static final float LABEL_MAX_DISTANCE_PT = 80f;
    /**
     * Filtre qualité d'un tableau à bordures. Le plancher de remplissage écarte seulement les grilles
     * dégénérées ; les vrais tableaux Word (cellules fusionnées) descendent à ~0,4, alors que les
     * grilles de figures denses sont écartées par la proximité d'une légende « Figure N ».
     */
    private static final int    MIN_BORDERED_CELLS = 6;
    private static final float  MIN_BORDERED_FILL  = 0.35f;

    private final PageGeometryAnalyzer geometry;

    public List<TableRegion> detect(byte[] pdfBytes) {
        List<TableRegion> regions = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            ObjectExtractor extractor = new ObjectExtractor(pdf);
            SpreadsheetExtractionAlgorithm bordered = new SpreadsheetExtractionAlgorithm();
            for (int p = 1; p <= pdf.getNumberOfPages(); p++) {
                try {
                    regions.addAll(detectPage(pdf, extractor, bordered, p));
                } catch (Exception e) {
                    log.warn("Table region detection failed on page {}: {}", p, e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("Table region detection skipped — cannot load PDF", e);
            return List.of();
        }
        log.info("Table region detection — {} region(s) total", regions.size());
        return regions;
    }

    private List<TableRegion> detectPage(PDDocument pdf, ObjectExtractor extractor,
                                         SpreadsheetExtractionAlgorithm bordered, int pageNum) throws IOException {
        PDPage pdPage = pdf.getPage(pageNum - 1);
        float pageWidth  = pdPage.getMediaBox().getWidth();
        float pageHeight = pdPage.getMediaBox().getHeight();

        List<Fragment> fragments = geometry.fragments(pdf, pageNum);
        if (fragments.isEmpty()) return List.of();

        List<TableRegion> regions = new ArrayList<>();
        List<Line> pageLines = toLines(fragments);   // toutes colonnes confondues : labellisation des boîtes

        // ── Ancre A : tableaux à bordures (Tabula spreadsheet) ──
        List<float[]> borderedBoxes = new ArrayList<>(); // [x0, y0, x1, y1]
        // Note : on n'utilise pas le gate bordered.isTabular() — trop strict ici (il renvoie false
        // sur des tableaux Word pourtant bien quadrillés). On part de extract() et on filtre par
        // qualité de grille, ce qui écarte aussi les figures/graphiques (grilles creuses).
        Page page = extractor.extract(pageNum);
        // Une page de figures (architecture, schémas) produit des grilles indiscernables d'un tableau :
        // on s'en sert pour rejeter les grilles sans légende « Table » qui y traînent.
        boolean pageHasFigure = pageLines.stream().anyMatch(l -> FIGURE_LABEL.matcher(l.text()).matches());
        for (Table t : bordered.extract(page)) {
            float[] q = borderedQuality(t);   // {rows, cols, fill, nonEmpty}
            int rows = (int) q[0], cols = (int) q[1], nonEmpty = (int) q[3];
            float fill = q[2];
            if (rows < 2 || cols < 2 || nonEmpty < MIN_BORDERED_CELLS || fill < MIN_BORDERED_FILL) continue;

            float x0 = (float) t.getX();
            float y0 = (float) t.getY();
            float x1 = x0 + (float) t.getWidth();
            float y1 = y0 + (float) t.getHeight();

            String caption = nearestCaption(pageLines, x0, y0, x1, y1, CAPTION_LABEL);
            // Sans légende « Table », une grille sur une page contenant une figure est un diagramme.
            if (caption == null && pageHasFigure) continue;

            borderedBoxes.add(new float[]{x0, y0, x1, y1});
            regions.add(new TableRegion(pageNum, x0, y0, x1, y1, caption, true, -1, rows));
        }

        // ── Ancre B : tableaux sans bordures (légende + géométrie), par colonne ──
        float[] splits = geometry.columnSplits(fragments, pageWidth, pageHeight);
        int numCols = splits.length + 1;
        for (int c = 0; c < numCols; c++) {
            List<Fragment> colFrags = columnFragments(fragments, splits, c);
            if (colFrags.isEmpty()) continue;
            regions.addAll(detectBorderlessInColumn(toLines(colFrags), c, pageNum, borderedBoxes));
        }

        return regions;
    }

    // =========================================================================
    // Ancre B — délimitation autour d'une légende, dans une colonne
    // =========================================================================

    private List<TableRegion> detectBorderlessInColumn(List<Line> lines, int col, int pageNum,
                                                       List<float[]> borderedBoxes) {
        List<TableRegion> out = new ArrayList<>();
        float medianGap = medianGap(lines);
        float maxGap = medianGap > 0 ? medianGap * MAX_ROW_GAP_FACTOR : MAX_ROW_GAP_FALLBACK_PT;

        for (int i = 0; i < lines.size(); i++) {
            Line anchor = lines.get(i);
            if (!CAPTION_ANCHOR.matcher(anchor.text()).matches()) continue;

            // La légende peut être au-dessus ou en dessous : on garde le côté au plus de lignes tabulaires.
            List<Line> up   = scan(lines, i, -1, maxGap);
            List<Line> down = scan(lines, i, +1, maxGap);
            List<Line> body = countTabular(up) >= countTabular(down) ? up : down;
            if (countTabular(body) < MIN_TABULAR_LINES) continue;

            float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            for (Line l : body) {
                x0 = Math.min(x0, l.xStart()); y0 = Math.min(y0, l.y());
                x1 = Math.max(x1, l.xEnd());   y1 = Math.max(y1, l.y());
            }
            if (overlapsBordered(x0, y0, x1, y1, borderedBoxes)) continue;

            out.add(new TableRegion(pageNum, x0, y0, x1, y1, anchor.text().strip(), false, col, countTabular(body)));
        }
        return out;
    }

    /**
     * Depuis la ligne d'ancrage, collecte les lignes consécutives dans le sens {@code dir}
     * (+1 = vers le bas, -1 = vers le haut) tant qu'on ne rencontre ni ligne prose ni grand
     * vide vertical. Les lignes non tabulaires courtes (sous-titres « Published », « Ours »…)
     * sont absorbées : seule la prose ou un gap coupe le tableau.
     */
    private List<Line> scan(List<Line> lines, int anchorIdx, int dir, float maxGap) {
        List<Line> run = new ArrayList<>();
        Line prev = lines.get(anchorIdx);
        for (int k = 1; k <= MAX_SCAN_LINES; k++) {
            int idx = anchorIdx + dir * k;
            if (idx < 0 || idx >= lines.size()) break;
            Line cur = lines.get(idx);
            float allowedGap = k == 1 ? Math.max(maxGap, FIRST_HOP_MIN_GAP_PT) : maxGap;
            if (Math.abs(cur.y() - prev.y()) > allowedGap) break;
            if (cur.prose()) break;
            run.add(cur);
            prev = cur;
        }
        return run;
    }

    private int countTabular(List<Line> lines) {
        int n = 0;
        for (Line l : lines) if (l.tabular()) n++;
        return n;
    }

    // =========================================================================
    // Lignes : regroupement par baseline + statistiques
    // =========================================================================

    /** Regroupe les fragments en lignes (baseline à {@link #LINE_Y_TOLERANCE_PT} près), triées par Y. */
    private List<Line> toLines(List<Fragment> frags) {
        List<Fragment> sorted = new ArrayList<>(frags);
        sorted.sort(Comparator.comparingDouble(Fragment::y).thenComparingDouble(Fragment::xStart));

        List<Line> lines = new ArrayList<>();
        List<Fragment> cur = new ArrayList<>();
        float curY = 0f;
        for (Fragment f : sorted) {
            if (cur.isEmpty()) {
                cur.add(f);
                curY = f.y();
            } else if (Math.abs(f.y() - curY) <= LINE_Y_TOLERANCE_PT) {
                cur.add(f);
            } else {
                lines.add(buildLine(cur));
                cur = new ArrayList<>();
                cur.add(f);
                curY = f.y();
            }
        }
        if (!cur.isEmpty()) lines.add(buildLine(cur));
        return lines;
    }

    private Line buildLine(List<Fragment> frags) {
        frags.sort(Comparator.comparingDouble(Fragment::xStart));
        float y      = frags.getFirst().y();
        float xStart = frags.getFirst().xStart();
        float xEnd   = xStart;

        int cells = 1;
        StringBuilder text = new StringBuilder();
        Fragment prev = null;
        for (Fragment f : frags) {
            if (prev != null) {
                if (f.xStart() - prev.xEnd() > CELL_GAP_PT) cells++;
                text.append(' ');
            }
            text.append(f.text());
            xEnd = Math.max(xEnd, f.xEnd());
            prev = f;
        }
        String t = text.toString().strip();

        int total = 0, numeric = 0;
        if (!t.isBlank()) {
            String[] toks = t.split("\\s+");
            total = toks.length;
            for (String tk : toks) if (HAS_DIGIT.matcher(tk).matches()) numeric++;
        }
        return new Line(y, xStart, xEnd, t, cells, numeric, total);
    }

    /** Interligne médian (Y) des lignes triées ; 0 si moins de 2 lignes. */
    private float medianGap(List<Line> lines) {
        if (lines.size() < 2) return 0f;
        List<Float> gaps = new ArrayList<>(lines.size() - 1);
        for (int i = 1; i < lines.size(); i++) gaps.add(Math.abs(lines.get(i).y() - lines.get(i - 1).y()));
        Collections.sort(gaps);
        return gaps.get(gaps.size() / 2);
    }

    // =========================================================================
    // Helpers — colonnes, bordures, labellisation
    // =========================================================================

    /** Fragments dont le midpoint X tombe dans la colonne {@code col} (bucketing identique à l'assembleur). */
    private List<Fragment> columnFragments(List<Fragment> frags, float[] splits, int col) {
        List<Fragment> out = new ArrayList<>();
        for (Fragment f : frags) {
            float midX = (f.xStart() + f.xEnd()) / 2f;
            int c = 0;
            while (c < splits.length && midX > splits[c]) c++;
            if (c == col) out.add(f);
        }
        return out;
    }

    /**
     * Qualité d'une grille Tabula à bordures, après rectangularisation et retrait des lignes/colonnes
     * vides : {@code {rows, cols, fill, nonEmpty}}. Un vrai tableau de données est dense et rectangulaire ;
     * une figure/un graphique donne une grille creuse ou dégénérée.
     */
    private float[] borderedQuality(Table t) {
        List<List<String>> grid = new ArrayList<>();
        for (List<RectangularTextContainer> row : t.getRows()) {
            List<String> cells = new ArrayList<>(row.size());
            for (RectangularTextContainer<?> cell : row) {
                String s = cell.getText();
                cells.add(s == null ? "" : s.strip());
            }
            grid.add(cells);
        }
        int width = grid.stream().mapToInt(List::size).max().orElse(0);
        for (List<String> r : grid) while (r.size() < width) r.add("");
        grid.removeIf(r -> r.stream().allMatch(String::isEmpty));
        for (int c = width - 1; c >= 0; c--) {
            final int col = c;
            if (!grid.isEmpty() && grid.stream().allMatch(r -> r.get(col).isEmpty())) {
                for (List<String> r : grid) r.remove(col);
            }
        }
        if (grid.isEmpty()) return new float[]{0, 0, 0, 0};
        int rows = grid.size();
        int cols = grid.getFirst().size();
        long nonEmpty = grid.stream().flatMap(List::stream).filter(s -> !s.isEmpty()).count();
        float fill = (float) nonEmpty / ((long) rows * cols);
        return new float[]{rows, cols, fill, nonEmpty};
    }

    /**
     * Légende correspondant à {@code pattern} la plus proche (au-dessus, en dessous ou à l'intérieur)
     * recouvrant la boîte en X, dans la limite de {@link #LABEL_MAX_DISTANCE_PT}. {@code null} si aucune.
     */
    private String nearestCaption(List<Line> pageLines, float x0, float y0, float x1, float y1, Pattern pattern) {
        String best = null;
        float bestDist = Float.MAX_VALUE;
        for (Line l : pageLines) {
            if (!pattern.matcher(l.text()).matches()) continue;
            if (l.xEnd() < x0 || l.xStart() > x1) continue;   // pas de recouvrement horizontal
            float dist = l.y() < y0 ? y0 - l.y() : (l.y() > y1 ? l.y() - y1 : 0f);
            if (dist < bestDist && dist <= LABEL_MAX_DISTANCE_PT) {
                bestDist = dist;
                best = l.text().strip();
            }
        }
        return best;
    }

    private boolean overlapsBordered(float x0, float y0, float x1, float y1, List<float[]> boxes) {
        for (float[] b : boxes) {
            boolean xov = x0 < b[2] && x1 > b[0];
            boolean yov = y0 < b[3] && y1 > b[1];
            if (xov && yov) return true;
        }
        return false;
    }

    // =========================================================================
    // Modèle interne d'une ligne
    // =========================================================================

    private record Line(float y, float xStart, float xEnd, String text,
                        int cells, int numericTokens, int totalTokens) {

        /** Au moins deux cellules alignées, ou une majorité de tokens numériques. */
        boolean tabular() {
            return cells >= 2 || (totalTokens > 0 && (double) numericTokens / totalTokens >= NUMERIC_RATIO);
        }

        /** Une cellule unique mais un texte long et continu : de la prose, pas une rangée. */
        boolean prose() {
            return cells < 2 && text.length() > PROSE_MIN_CHARS;
        }
    }
}
