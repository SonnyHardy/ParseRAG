package com.sonny.parserag.service.extraction;

import com.sonny.parserag.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Détection des lignes en désordre d'ordre de lecture (issue #30, palier 3), exercée sur des
 * séquences fabriquées.
 *
 * <p>Ce test existe parce que #31 a supprimé le seul cas réel qui déclenchait cette mécanique : la
 * page 1 de BERT n'entrelace plus ses colonnes. Sans lui, la détection serait du code que plus
 * aucun test ne couvre — et qui resterait pourtant le filet de sécurité si une mise en page
 * inconnue mettait à nouveau l'assemblage en défaut.
 */
class SuspectLineDetectionTest {

    private static final float PAGE_WIDTH = 600f;

    private final PdfTextExtractorService extractor =
            new PdfTextExtractorService(new AppProperties(), new PageGeometryAnalyzer());

    /** Deux colonnes entrelacées : chaque retour vers la gauche est un saut arrière anormal. */
    @Test
    void interleavedColumnsAreFlagged() {
        List<Float>  x     = List.of(80f, 320f, 80f, 320f, 80f, 320f);
        List<String> texts = List.of("gauche un", "droite un", "gauche deux", "droite deux",
                                     "gauche trois", "droite trois");

        Set<String> suspect = extractor.computeSuspectLines(x, texts, PAGE_WIDTH);

        assertEquals(Set.of("gauche deux", "gauche trois"), suspect,
                "seules les lignes qui reviennent brutalement à gauche sont suspectes");
    }

    /** Une mono-colonne saine : les lignes démarrent toutes au même X, aucun saut arrière. */
    @Test
    void cleanSingleColumnFlagsNothing() {
        List<Float>  x     = List.of(80f, 80f, 80f, 82f, 80f, 80f);
        List<String> texts = List.of("ligne un", "ligne deux", "ligne trois",
                                     "ligne quatre", "ligne cinq", "ligne six");

        assertTrue(extractor.computeSuspectLines(x, texts, PAGE_WIDTH).isEmpty());
    }

    /** Une indentation de paragraphe va vers la droite puis revient : l'écart reste sous le seuil. */
    @Test
    void paragraphIndentIsNotSuspect() {
        // Retrait de 20pt sur 600 de large = 3,3 %, très en deçà du seuil de 10 %.
        List<Float>  x     = List.of(80f, 100f, 80f, 80f, 100f, 80f);
        List<String> texts = List.of("ligne un", "ligne deux", "ligne trois",
                                     "ligne quatre", "ligne cinq", "ligne six");

        assertTrue(extractor.computeSuspectLines(x, texts, PAGE_WIDTH).isEmpty());
    }

    /** Sous 5 lignes, la page est trop courte pour juger de son ordre de lecture. */
    @Test
    void tooFewLinesToJudge() {
        List<Float>  x     = List.of(80f, 320f, 80f);
        List<String> texts = List.of("gauche un", "droite un", "gauche deux");

        assertTrue(extractor.computeSuspectLines(x, texts, PAGE_WIDTH).isEmpty());
    }

    // ── Faux positifs que l'ancien critère « tout saut arrière » produisait (#31) ─────────

    /**
     * Listing de code : les retours à gauche sont constants, mais les X se répartissent sur de
     * multiples niveaux d'indentation rapprochés — aucune paire d'ancres franchement séparée.
     */
    @Test
    void codeListingIsNotSuspect() {
        List<Float>  x     = List.of(80f, 100f, 120f, 100f, 80f, 140f, 100f, 80f);
        List<String> texts = List.of("def solve(n):", "total = 0", "for i in range(n):",
                                     "total += i", "return total", "print(solve(10))",
                                     "autre ligne", "fin du bloc");

        assertTrue(extractor.computeSuspectLines(x, texts, PAGE_WIDTH).isEmpty(),
                "un listing de code ne doit pas passer pour deux colonnes entrelacées");
    }

    /** Liste à puces : la puce et son texte sont séparés de 18pt, très en deçà des 15 % requis. */
    @Test
    void bulletListIsNotSuspect() {
        List<Float>  x     = List.of(90f, 108f, 90f, 108f, 90f, 108f);
        List<String> texts = List.of("premier point", "suite du premier", "deuxieme point",
                                     "suite du deuxieme", "troisieme point", "suite du troisieme");

        assertTrue(extractor.computeSuspectLines(x, texts, PAGE_WIDTH).isEmpty(),
                "une liste a puces alterne, mais ses deux ancres sont trop proches");
    }

    /**
     * Équation centrée isolée : l'ancre de droite existe et est bien séparée, mais ne porte qu'une
     * ligne sur sept — elle ne décrit pas une colonne.
     */
    @Test
    void isolatedCenteredLineIsNotSuspect() {
        List<Float>  x     = List.of(80f, 80f, 80f, 250f, 80f, 80f, 80f);
        List<String> texts = List.of("ligne un", "ligne deux", "ligne trois", "E = mc au carre",
                                     "ligne cinq", "ligne six", "ligne sept");

        assertTrue(extractor.computeSuspectLines(x, texts, PAGE_WIDTH).isEmpty(),
                "une seule ligne centree ne fait pas une colonne");
    }

    /** Un aller-retour unique (encadré, figure) n'est pas un entrelacement : il en faut la répétition. */
    @Test
    void singleRoundTripIsNotSuspect() {
        List<Float>  x     = List.of(80f, 80f, 320f, 320f, 80f, 80f, 80f, 320f);
        List<String> texts = List.of("ligne un", "ligne deux", "encadre un", "encadre deux",
                                     "ligne trois", "ligne quatre", "ligne cinq", "encadre trois");

        assertTrue(extractor.computeSuspectLines(x, texts, PAGE_WIDTH).isEmpty(),
                "deux bascules seulement : sous le seuil de repetition");
    }

    /**
     * Le bord d'une colonne n'est pas parfaitement régulier : des débuts de ligne qui varient de
     * quelques points doivent rester la MÊME ancre, sinon chaque variante passe sous le seuil de
     * représentativité (le cas qui faisait manquer resnet p5).
     */
    @Test
    void columnEdgeJitterStillFormsOneAnchor() {
        List<Float>  x     = List.of(80f, 320f, 84f, 309f, 80f, 317f, 82f, 305f);
        List<String> texts = List.of("gauche un", "droite un", "gauche deux", "droite deux",
                                     "gauche trois", "droite trois", "gauche quatre", "droite quatre");

        assertEquals(Set.of("gauche deux", "gauche trois", "gauche quatre"),
                extractor.computeSuspectLines(x, texts, PAGE_WIDTH),
                "les variations de quelques points ne doivent pas eclater l'ancre");
    }

    /** Les lignes très courtes ne sont pas retenues : trop ambiguës pour être attribuées à un chunk. */
    @Test
    void veryShortLinesAreIgnored() {
        List<Float>  x     = List.of(80f, 320f, 80f, 320f, 80f, 320f);
        List<String> texts = List.of("gauche un", "droite un", "ab", "droite deux",
                                     "cd", "droite trois");

        assertTrue(extractor.computeSuspectLines(x, texts, PAGE_WIDTH).isEmpty(),
                "les retours à gauche portent ici des lignes de moins de 5 caractères");
    }
}
