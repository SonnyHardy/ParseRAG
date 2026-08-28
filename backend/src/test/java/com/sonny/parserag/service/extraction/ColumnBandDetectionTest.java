package com.sonny.parserag.service.extraction;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Détection de colonnes par bandes (issue #31), sur de vraies pages du corpus.
 *
 * <p>Deux familles de cas, qui correspondent aux deux façons dont l'ancien algorithme se trompait :
 * <ul>
 *   <li>des pages 2-colonnes vues comme mono, parce qu'un élément traversait la gouttière — le bug
 *       de #31, qui entrelaçait les colonnes ;</li>
 *   <li>des pages mono-colonne vues comme multi, parce qu'une marge ou une indentation passait pour
 *       une gouttière — des faux positifs préexistants, restés invisibles tant que les colonnes
 *       fantômes étaient vides, mais que le nouvel algorithme devait impérativement ne pas aggraver.</li>
 * </ul>
 */
class ColumnBandDetectionTest {

    private final PageGeometryAnalyzer geometry = new PageGeometryAnalyzer();

    /** Nombre maximal de colonnes vu sur une bande de la page. */
    private int maxColumns(String pdf, int pageNum) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/sample-pdfs/pdfs/" + pdf)) {
            assertNotNull(in, "PDF introuvable : " + pdf);
            try (PDDocument doc = Loader.loadPDF(in.readAllBytes())) {
                PDPage page = doc.getPage(pageNum - 1);
                List<Band> bands = geometry.columnBands(
                        geometry.fragments(doc, pageNum),
                        page.getMediaBox().getWidth(),
                        page.getMediaBox().getHeight());
                return bands.stream().mapToInt(Band::columns).max().orElse(1);
            }
        }
    }

    // ── Pages 2-colonnes que l'ancien algorithme voyait mono ──────────────────────────────

    @Test
    void titlePagesWithFullWidthHeaderAreStillTwoColumns() throws IOException {
        // Les trois pages citées par l'issue : bandeau titre / tampon arXiv / bloc auteurs
        // traversent la gouttière et faisaient basculer toute la page en mono.
        assertEquals(2, maxColumns("arxiv-1810.04805-bert-2col.pdf", 1), "BERT p1");
        assertEquals(2, maxColumns("arxiv-1512.03385-resnet-2col.pdf", 1), "resnet p1");
        assertEquals(2, maxColumns("EnnsDocSymMod24_paper_7.pdf", 1), "EnnsDoc p1");
    }

    @Test
    void bodyPageWithFullWidthFigureIsStillTwoColumns() throws IOException {
        // Une légende de figure pleine largeur au milieu d'une page de corps : même cause,
        // sur une page qui n'est pas une page de titre.
        assertTrue(maxColumns("arxiv-1810.04805-bert-2col.pdf", 3) >= 2, "BERT p3");
    }

    @Test
    void plainTwoColumnPagesRemainTwoColumns() throws IOException {
        // Non-régression : ces pages étaient déjà correctement détectées avant #31.
        assertEquals(2, maxColumns("arxiv-1810.04805-bert-2col.pdf", 9), "BERT p9");
        assertEquals(2, maxColumns("EnnsDocSymMod24_paper_7.pdf", 4), "EnnsDoc p4");
    }

    // ── Pages mono-colonne : aucun découpage ne doit être inventé ─────────────────────────

    @Test
    void singleColumnPagesAreNeverSplit() throws IOException {
        assertEquals(1, maxColumns("thinkpython2-book-1col.pdf", 40), "thinkpython p40");
        assertEquals(1, maxColumns("nist-sp800-207-zerotrust.pdf", 20), "nist p20");
        assertEquals(1, maxColumns("eurlex-gdpr-fr.pdf", 10), "gdpr FR p10");
        assertEquals(1, maxColumns("eurlex-gdpr-de.pdf", 10), "gdpr DE p10");
    }

    /**
     * Faux positifs corrigés au passage : les marges de ce papier mono-colonne tombent dans la zone
     * de recherche et étaient lues comme deux gouttières — la page ressortait en « 3 colonnes ».
     * C'est la garde « texte des deux côtés » qui les élimine.
     */
    @Test
    void marginsAreNoLongerMistakenForGutters() throws IOException {
        assertEquals(1, maxColumns("arxiv-1706.03762-attention-1col.pdf", 1), "attention p1");
        assertEquals(1, maxColumns("arxiv-1706.03762-attention-1col.pdf", 4), "attention p4");
    }

    /** Une page presque vide ne doit pas être découpée sur un vide fortuit. */
    @Test
    void sparsePageIsNotSplit() throws IOException {
        assertEquals(1, maxColumns("thinkpython2-book-1col.pdf", 12), "thinkpython p12 (4 fragments)");
    }

    // ── Cohérence avec l'API consommée par la détection de tableaux ───────────────────────

    @Test
    void columnSplitsReflectsTheDominantBand() throws IOException {
        // TableRegionDetector n'a besoin que de la structure principale : elle doit correspondre à
        // la bande qui porte le plus de texte, pas à un bandeau titre de quelques lignes.
        try (InputStream in = getClass().getResourceAsStream("/sample-pdfs/pdfs/arxiv-1810.04805-bert-2col.pdf")) {
            assertNotNull(in);
            try (PDDocument doc = Loader.loadPDF(in.readAllBytes())) {
                PDPage page = doc.getPage(0);
                float w = page.getMediaBox().getWidth();
                float h = page.getMediaBox().getHeight();
                List<Fragment> fragments = geometry.fragments(doc, 1);

                assertEquals(1, geometry.columnSplits(fragments, w, h).length,
                        "la bande dominante de BERT p1 est le corps à 2 colonnes");
            }
        }
    }
}
