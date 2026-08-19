package com.sonny.parserag.service.extraction;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.model.domain.ExtractedDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boucle de vérification de l'ordre de lecture (issue #31), sur une mise en page conçue pour
 * mettre la géométrie en défaut : deux colonnes dont la gouttière est traversée en permanence par
 * des lignes pleine largeur.
 *
 * <p>Le contrat testé est le <strong>résultat</strong>, pas le chemin emprunté : que la géométrie
 * seule suffise ou que la boucle ait dû ré-assembler, le texte ne doit jamais sortir cousu. C'est
 * précisément la propriété qui rend l'extraction robuste à des mises en page jamais vues — un seuil
 * est calibré sur un corpus, une vérification contrôle la sortie réelle.
 *
 * <p>Les deux <strong>signatures</strong> d'entrelacement sont couvertes, car deux colonnes cousues
 * laissent une trace différente selon que leurs lignes de base coïncident : décalées, les débuts de
 * ligne alternent ; alignées, les colonnes fusionnent <em>dans</em> la même ligne, séparées par un
 * large blanc interne.
 */
class ReadingOrderRecoveryTest {

    private final PdfTextExtractorService extractor =
            new PdfTextExtractorService(props(), new PageGeometryAnalyzer());

    private static AppProperties props() {
        AppProperties p = new AppProperties();
        p.getPageLimits().setMaxPagesScale(50);
        return p;
    }

    /**
     * Page hostile : 24 lignes sur deux colonnes, et une ligne pleine largeur toutes les 4 lignes
     * pour saturer la gouttière. Les colonnes portent des textes reconnaissables afin de vérifier
     * l'ordre de lecture sans ambiguïté.
     */
    private static byte[] hostileTwoColumnPdf(boolean alignedBaselines) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            float height = page.getMediaBox().getHeight();

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                for (int row = 0; row < 24; row++) {
                    float y = height - 100 - row * 14;

                    if (row % 4 == 3) {                       // ligne pleine largeur : traverse tout
                        write(cs, 70, y, "BANDEAU pleine largeur numero " + row
                                + " qui traverse la gouttiere de part en part");
                        continue;
                    }
                    // La colonne de droite est décalée d'une demi-interligne : deux colonnes ne
                    // partagent pas leurs lignes de base dès que leur contenu diffère (figures,
                    // paragraphes de hauteurs inégales). C'est ce décalage qui fait alterner les
                    // débuts de ligne à l'assemblage — la signature que l'oracle reconnaît.
                    write(cs, 70, y, "GAUCHE ligne " + row + " du texte de la colonne de gauche");
                    write(cs, 320, alignedBaselines ? y : y - 7,
                            "DROITE ligne " + row + " du texte de la colonne de droite");
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static String flatten(String text) {
        return text.replaceAll("\s+", " ");
    }

    private static void write(PDPageContentStream cs, float x, float y, String text) throws Exception {
        cs.beginText();
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    @Test
    void hostileLayoutIsNeverInterleaved() throws Exception {
        ExtractedDocument doc = extractor.extract(hostileTwoColumnPdf(false), Plan.SCALE);
        String text = flatten(doc.pages().getFirst().rawText());

        // Deux lignes consécutives de la colonne gauche ne doivent pas être séparées par du texte
        // de la colonne droite — la signature exacte de l'entrelacement.
        int first  = text.indexOf("GAUCHE ligne 0");
        int second = text.indexOf("GAUCHE ligne 1");
        assertTrue(first >= 0 && second > first, "les deux lignes de gauche doivent etre presentes");
        assertTrue(!text.substring(first, second).contains("DROITE"),
                "du texte de la colonne droite s'est glisse entre deux lignes de gauche : "
                        + text.substring(first, Math.min(second + 20, text.length())));
    }

    /**
     * Seconde signature : lignes de base alignées, donc colonnes fusionnées dans la même ligne.
     * C'est le symptôme « customiza-<espaces>lack the necessary » cité par l'issue #31.
     */
    @Test
    void mergedColumnsLayoutIsNeverInterleaved() throws Exception {
        ExtractedDocument doc = extractor.extract(hostileTwoColumnPdf(true), Plan.SCALE);
        String text = flatten(doc.pages().getFirst().rawText());

        int first = text.indexOf("GAUCHE ligne 0");
        int second = text.indexOf("GAUCHE ligne 1");
        assertTrue(first >= 0 && second > first, "les deux lignes de gauche doivent etre presentes");
        assertTrue(!text.substring(first, second).contains("DROITE"),
                "les colonnes sont restees cousues dans la meme ligne : "
                        + text.substring(first, Math.min(second + 20, text.length())));
    }

    @Test
    void mergedColumnsLayoutLeavesNoSuspectLine() throws Exception {
        ExtractedDocument doc = extractor.extract(hostileTwoColumnPdf(true), Plan.SCALE);

        assertTrue(doc.pages().getFirst().reorderSuspectLines().isEmpty(),
                "lignes suspectes restantes : " + doc.pages().getFirst().reorderSuspectLines());
    }

    /** Le verdict d'ordre de lecture doit être propre : c'est ce que la boucle garantit. */
    @Test
    void hostileLayoutLeavesNoSuspectLine() throws Exception {
        ExtractedDocument doc = extractor.extract(hostileTwoColumnPdf(false), Plan.SCALE);

        assertTrue(doc.pages().getFirst().reorderSuspectLines().isEmpty(),
                "lignes suspectes restantes : " + doc.pages().getFirst().reorderSuspectLines());
    }
}
