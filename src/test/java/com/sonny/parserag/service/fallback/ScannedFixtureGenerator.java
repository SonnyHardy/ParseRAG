package com.sonny.parserag.service.fallback;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Générateur (manuel) des fixtures « scannées » pour la validation du fallback vision (issue #11).
 * Produit de vrais PDF <strong>image-only</strong> : le texte/les tableaux sont rastérisés (dessinés
 * en pixels), donc il n'existe aucune couche de texte natif — exactement ce qu'est un scan.
 *
 * <p>Désactivé en CI (effets de bord = écriture de fichiers). Lancer à la demande pour (re)générer :
 * {@code ./mvnw test -Dtest=ScannedFixtureGenerator -DfailIfNoTests=false} (retirer {@code @Disabled}).
 * Les fixtures couvrent : texte EN/FR, texte+tableau, multi-colonnes, document mixte natif+scanné,
 * document long (25 p. → budget/manual_review), figure quasi sans texte, page vide.
 */
@Disabled("Génération manuelle de fixtures — retirer @Disabled pour lancer")
class ScannedFixtureGenerator {

    private static final Path DIR = Path.of("src/test/resources/sample-pdfs/scanned");
    private static final int W = 850, H = 1100;   // ~ A4 @ 100 DPI

    @Test
    void generate() throws Exception {
        Files.createDirectories(DIR);

        // 1. Texte anglais, simple colonne, 2 pages.
        imageOnlyDoc("scanned-text-en.pdf", List.of(
                page(g -> textBlock(g, EN_1, 60, 90)),
                page(g -> textBlock(g, EN_2, 60, 90))));

        // 2. Texte français, 2 pages (couverture langue).
        imageOnlyDoc("scanned-text-fr.pdf", List.of(
                page(g -> textBlock(g, FR_1, 60, 90)),
                page(g -> textBlock(g, FR_2, 60, 90))));

        // 3. Texte + tableau (extraction text+tables).
        imageOnlyDoc("scanned-with-table.pdf", List.of(
                page(g -> {
                    textBlock(g, "Quarterly results summary.\nThe table below lists revenue per product.", 60, 90);
                    table(g, 60, 220,
                            new String[]{"Product", "Q1", "Q2", "Q3"},
                            new String[][]{{"Widget A", "120", "145", "160"},
                                    {"Widget B", "80", "92", "101"},
                                    {"Widget C", "60", "75", "88"}});
                })));

        // 4. Multi-colonnes (ordre de lecture).
        imageOnlyDoc("scanned-multicolumn.pdf", List.of(
                page(g -> {
                    textBlock(g, EN_1, 50, 90, 360);
                    textBlock(g, EN_2, 440, 90, 360);
                })));

        // 5. Document MIXTE : page 1 native (texte PDF réel), pages 2-3 scannées.
        mixedDoc("scanned-mixed.pdf");

        // 6. Document LONG : 25 pages scannées → exerce le cap 20 + manual_review sur 5.
        List<PageDrawer> longPages = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            final int n = i;
            longPages.add(g -> textBlock(g,
                    "Page " + n + " of a long scanned report.\n" + EN_1, 60, 90));
        }
        imageOnlyDoc("scanned-long-25p.pdf", longPages);

        // 7. Figure quasi sans texte (cas limite : peu/pas de texte, pas de tableau).
        imageOnlyDoc("scanned-figure-only.pdf", List.of(
                page(g -> {
                    g.setColor(new Color(220, 230, 245));
                    g.fillRect(150, 200, 540, 360);
                    g.setColor(Color.DARK_GRAY);
                    g.drawRect(150, 200, 540, 360);
                    g.setColor(Color.BLACK);
                    textBlock(g, "Figure 1", 380, 600);
                })));

        // 8. Page quasi vide (cas limite : vision ne renvoie rien d'exploitable).
        imageOnlyDoc("scanned-empty.pdf", List.of(page(g -> { /* page blanche */ })));

        System.out.println("Fixtures écrites dans " + DIR.toAbsolutePath());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface PageDrawer {
        void draw(Graphics2D g);
    }

    private static PageDrawer page(PageDrawer d) {
        return d;
    }

    /** Construit un PDF où chaque page est une image plein-cadre (aucun texte natif). */
    private static void imageOnlyDoc(String name, List<PageDrawer> pages) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (PageDrawer drawer : pages) {
                BufferedImage img = canvas();
                Graphics2D g = img.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Color.BLACK);
                g.setFont(new Font("Serif", Font.PLAIN, 16));
                drawer.draw(g);
                g.dispose();

                PDPage pdPage = new PDPage(PDRectangle.A4);
                doc.addPage(pdPage);
                PDImageXObject xobj = LosslessFactory.createFromImage(doc, img);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
                    cs.drawImage(xobj, 0, 0, pdPage.getMediaBox().getWidth(), pdPage.getMediaBox().getHeight());
                }
            }
            doc.save(DIR.resolve(name).toFile());
        }
    }

    /** Document mixte : 1 page de texte natif (couche texte réelle) + 2 pages scannées. */
    private static void mixedDoc(String name) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            // Page native (texte PDF réel → hasImages=false, détecteur : non scannée).
            PDPage native1 = new PDPage(PDRectangle.A4);
            doc.addPage(native1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, native1)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(60, 740);
                cs.setLeading(16);
                for (String line : ("This is a native (born-digital) cover page with a real text layer.\n"
                        + "It must NOT be treated as scanned.\nThe following pages are scanned images.")
                        .split("\n")) {
                    cs.showText(line);
                    cs.newLine();
                }
                cs.endText();
            }
            // 2 pages scannées.
            for (int i = 2; i <= 3; i++) {
                final int n = i;
                BufferedImage img = canvas();
                Graphics2D g = img.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Color.BLACK);
                g.setFont(new Font("Serif", Font.PLAIN, 16));
                textBlock(g, "Scanned page " + n + ".\n" + EN_2, 60, 90);
                g.dispose();

                PDPage scanned = new PDPage(PDRectangle.A4);
                doc.addPage(scanned);
                PDImageXObject xobj = LosslessFactory.createFromImage(doc, img);
                try (PDPageContentStream cs = new PDPageContentStream(doc, scanned)) {
                    cs.drawImage(xobj, 0, 0, scanned.getMediaBox().getWidth(), scanned.getMediaBox().getHeight());
                }
            }
            doc.save(DIR.resolve(name).toFile());
        }
    }

    private static BufferedImage canvas() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.dispose();
        return img;
    }

    private static void textBlock(Graphics2D g, String text, int x, int y) {
        textBlock(g, text, x, y, W - x - 50);
    }

    /** Dessine du texte avec retour à la ligne sur {@code maxWidth}. */
    private static void textBlock(Graphics2D g, String text, int x, int y, int maxWidth) {
        FontMetrics fm = g.getFontMetrics();
        int lineH = fm.getHeight() + 4;
        int cy = y;
        for (String paragraph : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String trial = line.isEmpty() ? word : line + " " + word;
                if (fm.stringWidth(trial) > maxWidth && !line.isEmpty()) {
                    g.drawString(line.toString(), x, cy);
                    cy += lineH;
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(trial);
                }
            }
            if (!line.isEmpty()) {
                g.drawString(line.toString(), x, cy);
                cy += lineH;
            }
            cy += lineH / 2;
        }
    }

    /** Dessine une grille simple (filets + texte des cellules). */
    private static void table(Graphics2D g, int x, int y, String[] headers, String[][] rows) {
        int cols = headers.length;
        int colW = 170, rowH = 34;
        int totalRows = rows.length + 1;
        g.setColor(Color.BLACK);
        for (int r = 0; r <= totalRows; r++) g.drawLine(x, y + r * rowH, x + cols * colW, y + r * rowH);
        for (int c = 0; c <= cols; c++) g.drawLine(x + c * colW, y, x + c * colW, y + totalRows * rowH);
        for (int c = 0; c < cols; c++) g.drawString(headers[c], x + 8 + c * colW, y + 22);
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < cols; c++) {
                g.drawString(rows[r][c], x + 8 + c * colW, y + 22 + (r + 1) * rowH);
            }
        }
    }

    private static final String EN_1 = """
            Retrieval-augmented generation pipelines depend on clean, well-segmented text. \
            When a source document is a scan, no native text layer exists, so optical \
            understanding becomes the only path to usable content for downstream embedding.""";
    private static final String EN_2 = """
            This page evaluates whether the vision fallback recovers readable paragraphs from a \
            rasterized image. The extracted text should preserve reading order and omit running \
            headers and footers that do not belong to the body.""";
    private static final String FR_1 = """
            Les pipelines de génération augmentée par récupération dépendent d'un texte propre et \
            bien segmenté. Lorsqu'un document source est un scan, aucune couche de texte natif \
            n'existe : la compréhension visuelle devient la seule voie vers un contenu exploitable.""";
    private static final String FR_2 = """
            Cette page vérifie que le repli vision restitue des paragraphes lisibles à partir d'une \
            image rastérisée. Le texte extrait doit préserver l'ordre de lecture et exclure les \
            en-têtes et pieds de page qui n'appartiennent pas au corps.""";
}
