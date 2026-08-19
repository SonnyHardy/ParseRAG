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
 * <p><strong>Limite connue de la fixture.</strong> Elle produit l'entrelacement <em>ligne à ligne</em>
 * (les débuts de ligne alternent), qui est la signature traitée par le palier 3 de #30. Quand deux
 * colonnes partagent exactement leurs lignes de base, elles fusionnent au contraire en une seule
 * ligne à espaces internes larges — une autre signature, relevée par le palier 1, et que la boucle
 * ne sait pas encore corriger.
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
    private static byte[] hostileTwoColumnPdf() throws Exception {
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
                    write(cs, 70,  y,     "GAUCHE ligne " + row + " du texte de la colonne de gauche");
                    write(cs, 320, y - 7, "DROITE ligne " + row + " du texte de la colonne de droite");
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void write(PDPageContentStream cs, float x, float y, String text) throws Exception {
        cs.beginText();
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    @Test
    void hostileLayoutIsNeverInterleaved() throws Exception {
        ExtractedDocument doc = extractor.extract(hostileTwoColumnPdf(), Plan.SCALE);
        String text = doc.pages().getFirst().rawText().replaceAll("\s+", " ");

        // Deux lignes consécutives de la colonne gauche ne doivent pas être séparées par du texte
        // de la colonne droite — la signature exacte de l'entrelacement.
        int first  = text.indexOf("GAUCHE ligne 0");
        int second = text.indexOf("GAUCHE ligne 1");
        assertTrue(first >= 0 && second > first, "les deux lignes de gauche doivent etre presentes");
        assertTrue(!text.substring(first, second).contains("DROITE"),
                "du texte de la colonne droite s'est glisse entre deux lignes de gauche : "
                        + text.substring(first, Math.min(second + 20, text.length())));
    }

    /** Le verdict d'ordre de lecture doit être propre : c'est ce que la boucle garantit. */
    @Test
    void hostileLayoutLeavesNoSuspectLine() throws Exception {
        ExtractedDocument doc = extractor.extract(hostileTwoColumnPdf(), Plan.SCALE);

        assertTrue(doc.pages().getFirst().reorderSuspectLines().isEmpty(),
                "lignes suspectes restantes : " + doc.pages().getFirst().reorderSuspectLines());
    }
}
