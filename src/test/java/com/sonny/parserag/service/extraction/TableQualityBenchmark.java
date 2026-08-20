package com.sonny.parserag.service.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.model.domain.TableRegion;
import com.sonny.parserag.model.domain.TableResult;
import com.sonny.parserag.observability.ParseRagMetrics;
import com.sonny.parserag.observability.TestMetrics;
import com.sonny.parserag.service.fallback.GeminiVisionFallbackService;
import com.sonny.parserag.service.fallback.VisionBudget;
import com.sonny.parserag.service.fallback.VisionResponseParser;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Relevé de la qualité des tableaux sur tout le corpus (issue #26).
 *
 * <p>Ce que ça mesure, et pourquoi : le portillon qui route une grille Tabula vers le fallback
 * vision arbitre entre deux erreurs opposées — partir pour rien (latence ~2,9 s par appel) et
 * rester muet sur une table cassée. Recalibrer ce portillon à l'intuition déplacerait l'erreur d'un
 * côté à l'autre sans qu'on le voie.
 *
 * <p>La colonne décisive est <strong>verdict</strong>, qui classe chaque appel réellement déclenché :
 * <ul>
 *   <li>{@code INUTILE} — la sortie vision est identique à celle de Tabula : l'appel n'apporte
 *       rien, on peut cesser de le déclencher ;</li>
 *   <li>{@code REPARATION} — les sorties diffèrent : le supprimer serait une régression.</li>
 * </ul>
 * C'est ce qui rend le critère « exactitude d'abord » mesurable plutôt que déclaratif.
 *
 * <p><strong>Appels vision réels et facturés</strong> quand {@code GOOGLE_API_KEY} est présent
 * (~10 sur le corpus). Sans clé le relevé reste utile : scores et routage sont mesurés, seuls les
 * verdicts manquent.
 * <pre>./mvnw test -Dtest=TableQualityBenchmark -DfailIfNoTests=false</pre>
 * Écrit son rapport dans {@code src/test/resources/sample-pdfs/results/} (répertoire gitignoré).
 */
@Disabled("Relevé manuel — appels vision réels et facturés ; retirer @Disabled pour lancer")
class TableQualityBenchmark {

    private static final Path PDFS    = Path.of("src/test/resources/sample-pdfs/pdfs");
    private static final Path RESULTS = Path.of("src/test/resources/sample-pdfs/results");

    /** Seuil de routage, aligné sur {@code parserag.vision.quality-threshold}. */
    private static final double QUALITY_THRESHOLD = 0.75;

    private record Row(String doc, int page, String caption, String tabulaDims, double brevity,
                       boolean defect, boolean routed, int stackedCells, String visionDims,
                       String verdict) {}

    private static AppProperties props(String geminiKey) {
        AppProperties p = new AppProperties();
        p.getExtraction().setStripLineNumbers(true);
        p.getPageLimits().setMaxPagesScale(2000);
        p.getTables().setEnabled(true);
        p.getTables().setMinRows(2);
        p.getTables().setMinCols(2);
        p.getTables().setMinFillRatio(0.4);
        p.getVision().setEnabled(true);
        p.getVision().setQualityThreshold(QUALITY_THRESHOLD);
        p.getVision().setMaxPagesPerDocument(50);
        p.getGemini().setApiKey(geminiKey);
        // Modele surchargeable comme dans VisionProviderBenchmark : c'est ce qui permet de
        // comparer deux modeles sur le meme corpus (issue #51).
        String model = System.getenv("GEMINI_MODEL");
        if (model != null && !model.isBlank()) p.getGemini().setModel(model);
        return p;
    }

    @Test
    void sweepCorpus() throws Exception {
        String key = geminiKey();
        System.out.println(key.isBlank()
                ? "Aucune cle Gemini : routage mesure, appels non classes."
                : "Cle Gemini presente : les appels seront classes INUTILE / REPARATION.");

        List<Row> rows = new ArrayList<>();
        for (Path pdf : Files.list(PDFS).filter(f -> f.toString().endsWith(".pdf")).sorted().toList()) {
            rows.addAll(measure(pdf, key));
        }

        Files.createDirectories(RESULTS);
        // Le modele fait partie du nom : deux passes le meme jour s'ecraseraient sans lui.
        Path report = RESULTS.resolve(
                "TABLES-" + props("").getGemini().getModel() + "-" + LocalDate.now() + ".md");
        Files.writeString(report, render(rows));
        System.out.println("Rapport ecrit : " + report.toAbsolutePath());

        System.out.printf("BILAN %d tableaux / %d routes vision (%d inutiles, %d reparations) / %d a cellules empilees%n",
                rows.size(),
                rows.stream().filter(Row::routed).count(),
                rows.stream().filter(r -> "INUTILE".equals(r.verdict())).count(),
                rows.stream().filter(r -> "REPARATION".equals(r.verdict())).count(),
                rows.stream().filter(r -> r.stackedCells() > 0).count());
    }

    /** Un passage sur un document : grille Tabula seule, puis vision autorisée, et on compare. */
    private List<Row> measure(Path pdf, String geminiKey) throws Exception {
        byte[] bytes = Files.readAllBytes(pdf);
        String name = pdf.getFileName().toString().replace(".pdf", "");

        AppProperties noVision = props("");
        PageGeometryAnalyzer geo = new PageGeometryAnalyzer();
        TableRegionDetector detector = new TableRegionDetector(geo);
        ParseRagMetrics metrics = TestMetrics.metrics();

        List<TableRegion> regions = detector.detect(bytes);
        if (regions.isEmpty()) return List.of();

        TableExtractorService tabulaOnly = new TableExtractorService(noVision, detector,
                new GeminiVisionFallbackService(noVision, parser(), metrics), metrics);
        ExtractedDocument doc = new PdfTextExtractorService(noVision, geo).extract(bytes, Plan.SCALE);
        List<TableResult> tabula = tabulaOnly.extract(bytes, doc, regions, new VisionBudget(0));

        List<TableResult> withVision = List.of();
        if (!geminiKey.isBlank()) {
            AppProperties visionOn = props(geminiKey);
            withVision = new TableExtractorService(visionOn, detector,
                    new GeminiVisionFallbackService(visionOn, parser(), metrics), metrics)
                    .extract(bytes, doc, regions, new VisionBudget(50));
        }

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < tabula.size(); i++) {
            TableResult t = tabula.get(i);
            double brevity = tabulaOnly.semanticQuality(t);
            boolean defect = tabulaOnly.hasStructuralDefect(t);
            boolean routed = brevity < QUALITY_THRESHOLD || defect;

            String visionDims = "-";
            String verdict = routed ? "?" : "non route";
            if (routed && i < withVision.size()) {
                TableResult v = withVision.get(i);
                visionDims = v.rowCount() + "x" + v.colCount();
                verdict = sameGrid(t, v) ? "INUTILE" : "REPARATION";
            }

            rows.add(new Row(name, t.page(), t.caption(), t.rowCount() + "x" + t.colCount(),
                    brevity, defect, routed, countStacked(t), visionDims, verdict));
        }
        return rows;
    }

    private static VisionResponseParser parser() {
        return new VisionResponseParser(new ObjectMapper());
    }

    /** Deux grilles sont « identiques » si en-têtes et lignes coïncident cellule à cellule. */
    private static boolean sameGrid(TableResult a, TableResult b) {
        return a.headers().equals(b.headers()) && a.rows().equals(b.rows());
    }

    private static int countStacked(TableResult t) {
        int stacked = 0;
        for (String c : t.headers()) if (isStacked(c)) stacked++;
        for (List<String> row : t.rows()) for (String c : row) if (isStacked(c)) stacked++;
        return stacked;
    }

    private static boolean isStacked(String cell) {
        return cell != null && (cell.indexOf('\r') >= 0 || cell.indexOf('\n') >= 0);
    }

    /** La clé vient de l'environnement ou du {@code .env} du projet — jamais codée en dur. */
    private static String geminiKey() throws Exception {
        String env = System.getenv("GOOGLE_API_KEY");
        if (env != null && !env.isBlank()) return env;
        Path dotenv = Path.of(".env");
        if (!Files.exists(dotenv)) return "";
        for (String line : Files.readAllLines(dotenv)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("GOOGLE_API_KEY=")) {
                return trimmed.substring("GOOGLE_API_KEY=".length()).strip();
            }
        }
        return "";
    }

    private String render(List<Row> rows) {
        StringBuilder md = new StringBuilder();
        md.append("# Qualité des tableaux — ").append(LocalDate.now()).append("\n\n");
        md.append("Issue #26. `brièveté` = score `semanticQuality` ; routage vision sous ")
          .append(QUALITY_THRESHOLD).append(" ou sur défaut structurel.\n");
        md.append("`verdict` classe les appels déclenchés : **INUTILE** = sortie vision identique à ")
          .append("Tabula, **REPARATION** = sorties différentes.\n\n");

        md.append("**%d tableaux · %d routés vision · %d inutiles · %d réparations · %d à cellules empilées**\n\n"
                .formatted(rows.size(),
                        rows.stream().filter(Row::routed).count(),
                        rows.stream().filter(r -> "INUTILE".equals(r.verdict())).count(),
                        rows.stream().filter(r -> "REPARATION".equals(r.verdict())).count(),
                        rows.stream().filter(r -> r.stackedCells() > 0).count()));

        md.append("| Document | Page | Tabula | Brièveté | Défaut | Routé | Empilées | Vision | Verdict | Légende |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            md.append("| %s | %d | %s | %.2f | %s | %s | %d | %s | %s | %s |\n".formatted(
                    r.doc(), r.page(), r.tabulaDims(), r.brevity(),
                    r.defect() ? "oui" : "", r.routed() ? "**oui**" : "",
                    r.stackedCells(), r.visionDims(), r.verdict(),
                    r.caption() == null ? "" : r.caption().replace("|", "/")));
        }
        return md.toString();
    }
}
