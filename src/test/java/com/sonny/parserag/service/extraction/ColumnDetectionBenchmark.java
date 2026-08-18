package com.sonny.parserag.service.extraction;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.model.domain.ExtractedPage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Relevé page par page de la détection de colonnes sur tout le corpus (issue #31).
 *
 * <p>Ce que ça mesure, et pourquoi : le correctif de #31 touche le cœur de l'extraction, consommé
 * à la fois par le texte et par la détection de tableaux. Les tests ciblés couvrent une dizaine de
 * pages ; ce harnais couvre tout le corpus et rend visible ce qui bouge <em>ailleurs</em> — c'est
 * là que se cachent les régressions d'un changement de géométrie.
 *
 * <p>Deux colonnes comptent :
 * <ul>
 *   <li><strong>bandes</strong> : la structure détectée (ex. {@code 1c/13r 2c/66r} = un bandeau
 *       mono de 13 lignes puis un corps à 2 colonnes de 66 lignes) ;</li>
 *   <li><strong>suspectes</strong> : lignes en désordre d'ordre de lecture (#30). C'est l'oracle —
 *       il doit rester à zéro partout.</li>
 * </ul>
 *
 * <p>Purement local, aucun appel réseau, mais lent (tout le corpus est ré-extrait deux fois) et
 * bavard : lancé à la demande plutôt qu'en CI.
 * <pre>./mvnw test -Dtest=ColumnDetectionBenchmark -DfailIfNoTests=false</pre>
 * Écrit son rapport dans {@code src/test/resources/sample-pdfs/results/} (répertoire gitignoré).
 */
@Disabled("Relevé manuel sur tout le corpus — lent et verbeux ; retirer @Disabled pour lancer")
class ColumnDetectionBenchmark {

    private static final Path PDFS    = Path.of("src/test/resources/sample-pdfs/pdfs");
    private static final Path RESULTS = Path.of("src/test/resources/sample-pdfs/results");

    private final PageGeometryAnalyzer geometry = new PageGeometryAnalyzer();
    private final PdfTextExtractorService extractor = new PdfTextExtractorService(props(), geometry);

    private static AppProperties props() {
        AppProperties p = new AppProperties();
        p.getExtraction().setStripLineNumbers(true);
        p.getPageLimits().setMaxPagesScale(2000);
        return p;
    }

    private record PageRow(String doc, int page, String bands, int columns, int suspect, int chars) {}

    @Test
    void sweepCorpus() throws Exception {
        List<PageRow> rows = new ArrayList<>();

        List<Path> pdfs = Files.list(PDFS).filter(p -> p.toString().endsWith(".pdf")).sorted().toList();
        for (Path pdf : pdfs) {
            byte[] bytes = Files.readAllBytes(pdf);
            String name = pdf.getFileName().toString().replace(".pdf", "");

            // Structure géométrique, page par page.
            List<String> bandDesc = new ArrayList<>();
            List<Integer> cols = new ArrayList<>();
            try (PDDocument doc = Loader.loadPDF(bytes)) {
                for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                    PDPage page = doc.getPage(i - 1);
                    List<Band> bands = geometry.columnBands(geometry.fragments(doc, i),
                            page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                    bandDesc.add(bands.stream().map(b -> b.columns() + "c/" + b.rows() + "r")
                            .reduce((a, b) -> a + " " + b).orElse("-"));
                    cols.add(bands.stream().mapToInt(Band::columns).max().orElse(1));
                }
            }

            // Texte extrait : c'est lui qui porte les lignes suspectes.
            ExtractedDocument extracted = extractor.extract(bytes, Plan.SCALE);
            for (ExtractedPage page : extracted.pages()) {
                int idx = page.pageNumber() - 1;
                rows.add(new PageRow(name, page.pageNumber(),
                        idx < bandDesc.size() ? bandDesc.get(idx) : "?",
                        idx < cols.size() ? cols.get(idx) : 1,
                        page.reorderSuspectLines().size(),
                        page.rawText() == null ? 0 : page.rawText().length()));
            }
        }

        Files.createDirectories(RESULTS);
        Path report = RESULTS.resolve("COLUMNS-" + LocalDate.now() + ".md");
        Files.writeString(report, render(rows));
        System.out.println("Rapport ecrit : " + report.toAbsolutePath());

        // Résumé console, pour ne pas avoir à ouvrir le fichier dans le cas nominal.
        int totalSuspect = rows.stream().mapToInt(PageRow::suspect).sum();
        long pagesWithSuspect = rows.stream().filter(r -> r.suspect() > 0).count();
        long multiCol = rows.stream().filter(r -> r.columns() > 1).count();
        System.out.printf("BILAN %d pages / %d multi-colonnes / %d pages avec lignes suspectes (%d au total)%n",
                rows.size(), multiCol, pagesWithSuspect, totalSuspect);
    }

    private String render(List<PageRow> rows) {
        StringBuilder md = new StringBuilder();
        md.append("# Détection de colonnes par bandes — ").append(LocalDate.now()).append("\n\n");
        md.append("Issue #31. `bandes` = structure détectée (`2c/66r` = 2 colonnes sur 66 baselines).\n");
        md.append("`suspectes` = lignes en désordre d'ordre de lecture (#30) : **doit rester à 0**.\n\n");

        int totalSuspect = rows.stream().mapToInt(PageRow::suspect).sum();
        long pagesWithSuspect = rows.stream().filter(r -> r.suspect() > 0).count();
        md.append("**%d pages · %d en multi-colonnes · %d pages avec lignes suspectes (%d lignes)**\n\n"
                .formatted(rows.size(), rows.stream().filter(r -> r.columns() > 1).count(),
                        pagesWithSuspect, totalSuspect));

        if (pagesWithSuspect > 0) {
            md.append("## Pages à lignes suspectes\n\n| Document | Page | Bandes | Suspectes |\n|---|---|---|---|\n");
            rows.stream().filter(r -> r.suspect() > 0).forEach(r ->
                    md.append("| %s | %d | %s | **%d** |\n".formatted(r.doc(), r.page(), r.bands(), r.suspect())));
            md.append('\n');
        }

        md.append("## Détail par page\n\n| Document | Page | Bandes | Colonnes | Suspectes | Caractères |\n");
        md.append("|---|---|---|---|---|---|\n");
        for (PageRow r : rows) {
            md.append("| %s | %d | %s | %d | %d | %d |\n".formatted(
                    r.doc(), r.page(), r.bands(), r.columns(), r.suspect(), r.chars()));
        }
        return md.toString();
    }
}
