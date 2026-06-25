package com.sonny.parserag.service.extraction;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.model.domain.TableRegion;
import com.sonny.parserag.model.domain.TableResult;
import com.sonny.parserag.service.fallback.VisionFallbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.ExtractionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Extrait les tableaux d'un PDF natif (issue #9) en {@link TableResult} structurés, par
 * <strong>routage des régions</strong> détectées par {@link TableRegionDetector} :
 *
 * <ul>
 *   <li><strong>bordered</strong> → {@link SpreadsheetExtractionAlgorithm} ciblé sur le rectangle
 *       de la région (les filets guident le découpage) ;</li>
 *   <li><strong>borderless</strong> → {@link BasicExtractionAlgorithm} ciblé sur la région (gratuit,
 *       hors-ligne) ; si la grille obtenue est absente ou de mauvaise qualité
 *       ({@code confidence < vision.confidence-threshold}) et que le fallback vision est disponible,
 *       on rend l'image de la région et on délègue à {@link VisionFallbackService}.</li>
 * </ul>
 *
 * <p>Cibler une <em>région</em> (et non la page entière) est ce qui rend Tabula fiable sur un papier
 * scientifique multi-colonnes. Le filtrage qualité ({@link #buildFromGrid}) écarte les faux positifs.
 * Le nombre d'appels vision par document est plafonné par {@code vision.max-pages-per-document}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableExtractorService {

    private static final double BORDERED_BASE  = 0.9;
    private static final double BORDERLESS_BASE = 0.7;

    /** Résolution de rendu d'une région pour le fallback vision. */
    private static final float RENDER_DPI = 150f;
    /** Marge (pt) ajoutée autour de la région avant rognage de l'image. */
    private static final float REGION_PADDING_PT = 4f;
    /** Marge (pt) ajoutée au rectangle passé à Tabula {@code getArea} (évite de rogner les bords). */
    private static final float AREA_PADDING_PT = 3f;

    /** Seuils de « cellule brève » pour le score de qualité sémantique. */
    private static final int MAX_CELL_WORDS = 4;
    private static final int MAX_CELL_CHARS = 28;

    /** Un token/cellule « numérique » contient au moins un chiffre. */
    private static final Pattern HAS_DIGIT = Pattern.compile(".*\\d.*");
    /** Caractère de remplacement Unicode (glyphe non décodé) : signe d'extraction défaillante. */
    private static final char REPLACEMENT_CHAR = '�';
    /** Remplissage en deçà duquel une colonne est « creuse » → trahit un découpage en colonnes fantômes. */
    private static final double HOLLOW_COLUMN_FILL = 0.2;
    /** Ratio de cellules d'en-tête numériques au-delà duquel l'en-tête est jugé « happé » (mauvaise ligne). */
    private static final double NUMERIC_HEADER_RATIO = 0.6;

    private final AppProperties appProperties;
    private final TableRegionDetector tableRegionDetector;
    private final VisionFallbackService visionFallbackService;

    /** Détecte les régions puis extrait (chemin autonome, ex. tests). */
    public List<TableResult> extract(byte[] pdfBytes, ExtractedDocument doc) {
        if (!appProperties.getTables().isEnabled() || doc == null) return List.of();
        return extract(pdfBytes, doc, tableRegionDetector.detect(pdfBytes));
    }

    /**
     * Extrait à partir de régions <em>déjà détectées</em> (le pipeline les partage avec
     * {@link TableTextStripper}, évitant une seconde détection).
     */
    public List<TableResult> extract(byte[] pdfBytes, ExtractedDocument doc, List<TableRegion> regions) {
        AppProperties.Tables cfg = appProperties.getTables();
        if (!cfg.isEnabled() || doc == null || regions == null || regions.isEmpty()) {
            return List.of();
        }

        AppProperties.Vision vcfg = appProperties.getVision();
        double qualityThreshold = vcfg.getQualityThreshold();
        int visionBudget = vcfg.getMaxPagesPerDocument();
        int visionUsed = 0;

        List<TableResult> results = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            ObjectExtractor extractor = new ObjectExtractor(pdf);
            SpreadsheetExtractionAlgorithm bordered = new SpreadsheetExtractionAlgorithm();
            BasicExtractionAlgorithm borderless = new BasicExtractionAlgorithm();
            PDFRenderer renderer = new PDFRenderer(pdf);

            for (TableRegion region : regions) {
                try {
                    ExtractionAlgorithm algo = region.bordered() ? bordered : borderless;
                    TableResult tr = extractTabula(extractor, algo, region, cfg);

                    // Fallback vision sur grille absente/médiocre — bordered comme borderless
                    // (un tableau à filets aux en-têtes éclatés bénéficie aussi de la vision).
                    boolean poor = tr == null
                            || semanticQuality(tr) < qualityThreshold
                            || hasStructuralDefect(tr);
                    if (poor && visionFallbackService.isAvailable() && visionUsed < visionBudget) {
                        byte[] img = renderRegion(renderer, region);
                        TableResult vision = visionFallbackService.extractTable(img, region.page(), region.caption());
                        if (vision != null) {
                            tr = vision;
                            visionUsed++;
                        }
                    }
                    if (tr != null) results.add(tr);
                } catch (Exception e) {
                    log.warn("Table extraction failed on region p{} (docId {}): {}",
                            region.page(), doc.documentId(), e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("Table extraction skipped — cannot load PDF (docId {})", doc.documentId(), e);
            return List.of();
        }

        log.info("Table extraction — docId: {}, regions: {}, tables: {}, vision: {}",
                doc.documentId(), regions.size(), results.size(), visionUsed);
        return results;
    }

    /** Extraction Tabula ciblée sur le rectangle d'une région, puis filtrage qualité. */
    private TableResult extractTabula(ObjectExtractor extractor, ExtractionAlgorithm algo,
                                      TableRegion region, AppProperties.Tables cfg) {
        Page page = extractor.extract(region.page());
        // Tabula : getArea(top, left, bottom, right) — mêmes coordonnées (pt, origine haut-gauche).
        // Petite marge pour ne pas rogner les caractères de bord (ex. « Pre-OpenAI » → « re-OpenAI »).
        float top    = Math.max(0, region.y0() - AREA_PADDING_PT);
        float left   = Math.max(0, region.x0() - AREA_PADDING_PT);
        float bottom = region.y1() + AREA_PADDING_PT;
        float right  = region.x1() + AREA_PADDING_PT;
        Page area = page.getArea(top, left, bottom, right);
        List<? extends Table> tables = algo.extract(area);
        if (tables.isEmpty()) return null;

        List<List<String>> grid = new ArrayList<>();
        for (Table t : tables) grid.addAll(toGrid(t));   // souvent une seule table par région
        return buildFromGrid(grid, region.page(), region.caption(), !region.bordered(), cfg);
    }

    /** Rend la région en PNG (page entière à {@link #RENDER_DPI}, puis rognage + marge). */
    private byte[] renderRegion(PDFRenderer renderer, TableRegion region) throws IOException {
        float scale = RENDER_DPI / 72f;
        BufferedImage pageImg = renderer.renderImageWithDPI(region.page() - 1, RENDER_DPI);

        int x = Math.round((region.x0() - REGION_PADDING_PT) * scale);
        int y = Math.round((region.y0() - REGION_PADDING_PT) * scale);
        int w = Math.round((region.x1() - region.x0() + 2 * REGION_PADDING_PT) * scale);
        int h = Math.round((region.y1() - region.y0() + 2 * REGION_PADDING_PT) * scale);

        x = Math.max(0, x);
        y = Math.max(0, y);
        w = Math.min(pageImg.getWidth() - x, w);
        h = Math.min(pageImg.getHeight() - y, h);
        if (w <= 0 || h <= 0) return null;

        BufferedImage crop = pageImg.getSubimage(x, y, w, h);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(crop, "png", out);
        return out.toByteArray();
    }

    /** Convertit une {@link Table} Tabula en grille de chaînes brute. */
    private List<List<String>> toGrid(Table table) {
        List<List<String>> grid = new ArrayList<>();
        for (List<RectangularTextContainer> row : table.getRows()) {
            List<String> cells = new ArrayList<>(row.size());
            for (RectangularTextContainer<?> cell : row) {
                String text = cell.getText();
                cells.add(text == null ? "" : text.strip());
            }
            grid.add(cells);
        }
        return grid;
    }

    /**
     * Normalise (rectangularise + retire lignes/colonnes vides), applique le filtrage qualité,
     * puis produit un {@link TableResult}. Retourne {@code null} si la grille n'est pas un vrai tableau.
     * Méthode pure (testable sans Tabula).
     */
    TableResult buildFromGrid(List<List<String>> rawGrid, int page, String caption,
                              boolean borderless, AppProperties.Tables cfg) {
        // 1. Rectangulariser à la largeur max.
        int width = rawGrid.stream().mapToInt(List::size).max().orElse(0);
        List<List<String>> grid = new ArrayList<>();
        for (List<String> row : rawGrid) {
            List<String> r = new ArrayList<>(row);
            while (r.size() < width) r.add("");
            grid.add(r);
        }
        // 2. Retirer les lignes entièrement vides.
        grid.removeIf(r -> r.stream().allMatch(String::isEmpty));
        // 3. Retirer les colonnes entièrement vides.
        for (int c = width - 1; c >= 0; c--) {
            final int col = c;
            boolean empty = grid.stream().allMatch(r -> r.get(col).isEmpty());
            if (empty) {
                for (List<String> r : grid) r.remove(col);
            }
        }
        if (grid.isEmpty()) return null;

        // 3b. Sauter d'éventuelles lignes « super-en-tête » éparses en tête (une seule cellule,
        //     ex. un titre fusionné au-dessus du vrai en-tête) tant que la ligne suivante est plus dense.
        int h = 0;
        while (h < grid.size() - 1 && h < 3
                && nonEmptyCount(grid.get(h)) <= 1
                && nonEmptyCount(grid.get(h + 1)) > nonEmptyCount(grid.get(h))) {
            h++;
        }
        if (h > 0) grid = new ArrayList<>(grid.subList(h, grid.size()));

        int cols = grid.getFirst().size();

        // 4. Filtrage qualité.
        if (grid.size() < cfg.getMinRows() || cols < cfg.getMinCols()) return null;
        if (looksLikeReferenceList(grid)) return null;   // « [1] … [2] … » = bibliographie, pas un tableau
        long nonEmpty = grid.stream().flatMap(List::stream).filter(s -> !s.isEmpty()).count();
        double fill = (double) nonEmpty / ((long) grid.size() * cols);
        if (fill < cfg.getMinFillRatio()) return null;

        // 5. Découpe en-tête / données + confiance.
        List<String> headers = grid.getFirst();
        List<List<String>> dataRows = new ArrayList<>(grid.subList(1, grid.size()));
        double confidence = tableConfidence(borderless, fill);

        return new TableResult(page, caption, headers, dataRows, grid.size(), cols, confidence, false);
    }

    private int nonEmptyCount(List<String> row) {
        int n = 0;
        for (String c : row) if (c != null && !c.isEmpty()) n++;
        return n;
    }

    /** Vrai si la 1ʳᵉ colonne est majoritairement « [n] » → liste bibliographique, pas un tableau. */
    private boolean looksLikeReferenceList(List<List<String>> grid) {
        long bracketed = grid.stream()
                .map(r -> r.getFirst().strip())
                .filter(s -> s.matches("\\[\\d{1,3}\\]"))
                .count();
        return (double) bracketed / grid.size() >= 0.6;
    }

    /**
     * Qualité <em>sémantique</em> d'une grille Tabula (distincte du remplissage) : une vraie table a des
     * cellules <strong>brèves</strong> et peu de lignes <strong>singleton</strong> (= ligne de prose
     * happée, une seule cellule non vide). Sert à décider du fallback vision : une grille polluée par
     * de la prose (en-têtes = fragments de phrase) obtient un score bas même si elle est « pleine ».
     */
    double semanticQuality(TableResult t) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(t.headers());
        rows.addAll(t.rows());

        long nonEmpty = 0, shortCells = 0, singletonRows = 0;
        for (List<String> r : rows) {
            int ne = 0;
            for (String c : r) {
                if (c == null || c.isBlank()) continue;
                ne++;
                nonEmpty++;
                int words = c.strip().split("\\s+").length;
                if (words <= MAX_CELL_WORDS && c.length() <= MAX_CELL_CHARS) shortCells++;
            }
            if (ne <= 1) singletonRows++;
        }
        if (nonEmpty == 0) return 0;
        double brevity = (double) shortCells / nonEmpty;
        double singletonFrac = (double) singletonRows / rows.size();
        return brevity * (1.0 - singletonFrac);
    }

    /**
     * Défauts de <em>structure</em> que le score de brièveté ne voit pas, et qui justifient le fallback
     * vision même sur une grille « pleine » et « brève » :
     * <ol>
     *   <li>glyphe non décodé ({@code �}) n'importe où ;</li>
     *   <li>en-tête majoritairement numérique (≥ 3 colonnes) → la vraie ligne d'en-tête a été ratée
     *       (ex. tailles {@code 392k…} happées à la place des noms de tâches) ;</li>
     *   <li>colonne « creuse » hors 1ʳᵉ colonne → colonnes fantômes d'un mauvais découpage Tabula
     *       (ex. nombres centrés éclatés sur des colonnes vides intercalées).</li>
     * </ol>
     */
    boolean hasStructuralDefect(TableResult t) {
        List<String> headers = t.headers();
        List<List<String>> rows = t.rows();
        int cols = headers.size();
        if (cols < 2) return false;

        // 1. Glyphe non décodé.
        if (containsReplacementChar(headers)) return true;
        for (List<String> r : rows) if (containsReplacementChar(r)) return true;

        // 2. En-tête majoritairement numérique ET 1ʳᵉ cellule vide : signature d'une ligne d'en-tête
        //    mal alignée (ligne de données/tailles happée comme en-tête). La garde « 1ʳᵉ cellule vide »
        //    épargne les tables à en-têtes légitimement numériques (années, tailles, # couches) dont
        //    la colonne de libellés est étiquetée → évite le faux positif.
        String first = headers.getFirst();
        boolean firstBlank = first == null || first.isBlank();
        long headerNonEmpty = headers.stream().filter(s -> s != null && !s.isBlank()).count();
        long headerNumeric = headers.stream()
                .filter(s -> s != null && !s.isBlank() && HAS_DIGIT.matcher(s).matches())
                .count();
        if (firstBlank && cols >= 3 && headerNonEmpty > 0
                && (double) headerNumeric / headerNonEmpty >= NUMERIC_HEADER_RATIO) {
            return true;
        }

        // 3. Colonne creuse (hors 1ʳᵉ colonne, souvent le libellé de ligne).
        int total = rows.size() + 1;   // en-tête + données
        for (int c = 1; c < cols; c++) {
            int filled = isCellFilled(headers, c) ? 1 : 0;
            for (List<String> r : rows) if (isCellFilled(r, c)) filled++;
            if ((double) filled / total <= HOLLOW_COLUMN_FILL) return true;
        }
        return false;
    }

    private boolean containsReplacementChar(List<String> cells) {
        for (String c : cells) if (c != null && c.indexOf(REPLACEMENT_CHAR) >= 0) return true;
        return false;
    }

    private boolean isCellFilled(List<String> row, int c) {
        return c < row.size() && row.get(c) != null && !row.get(c).isBlank();
    }

    /** Base selon la méthode, modulée par le taux de remplissage (même esprit que le score de chunk). */
    private double tableConfidence(boolean borderless, double fill) {
        double base = borderless ? BORDERLESS_BASE : BORDERED_BASE;
        double confidence = base * (0.6 + 0.4 * fill);
        return Math.round(Math.min(1.0, confidence) * 100.0) / 100.0;
    }
}
