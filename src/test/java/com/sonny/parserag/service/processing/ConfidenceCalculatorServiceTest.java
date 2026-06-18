package com.sonny.parserag.service.processing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du score de confiance (issue #8). Les attentes sont cohérentes avec la formule
 * {@code 0.4·density + 0.3·length + 0.3·coherence} et orientées « réalisme » : un chunk
 * propre score haut, un fragment/continuation score nettement plus bas.
 */
class ConfidenceCalculatorServiceTest {

    private final ConfidenceCalculatorService calc = new ConfidenceCalculatorService();

    private static final String CLEAN_PARAGRAPH =
            ("Zero trust architecture assumes no implicit trust and continuously evaluates "
           + "each access request based on identity, device posture and context. ").repeat(3);

    // ── calculate() — comportement global ────────────────────────────────────

    @Test
    void cleanParagraphScoresHigh() {
        double score = calc.calculate(CLEAN_PARAGRAPH);
        assertTrue(score >= 0.85, "prose propre devrait scorer haut, obtenu: " + score);
    }

    @Test
    void finalPunctuationRaisesScore() {
        String withPunct = "The system validates every request before granting access to any resource.";
        String withoutPunct = withPunct.substring(0, withPunct.length() - 1); // retire le point final
        assertTrue(calc.calculate(withPunct) > calc.calculate(withoutPunct),
                "la ponctuation finale doit augmenter le score");
    }

    @Test
    void midParagraphContinuationScoresLowerThanCleanParagraph() {
        // Commence en minuscule, sans ponctuation finale (typique d'un chunk d'overlap).
        String continuation =
                "ngs eines nutzers ausgeben zu lassen und einen song von einem nutzer zu entfernen";
        assertTrue(calc.calculate(continuation) < calc.calculate(CLEAN_PARAGRAPH),
                "une continuation mi-paragraphe doit scorer plus bas qu'un paragraphe propre");
    }

    @Test
    void shortArtifactScoresLow() {
        assertTrue(calc.calculate("12") < 0.5, "un artefact très court doit scorer bas");
        assertTrue(calc.calculate("3 . 5 . 7") < calc.calculate(CLEAN_PARAGRAPH));
    }

    @Test
    void nullOrBlankIsZero() {
        assertEquals(0.0, calc.calculate(null));
        assertEquals(0.0, calc.calculate("   "));
    }

    @Test
    void resultRoundedToTwoDecimals() {
        double score = calc.calculate(CLEAN_PARAGRAPH);
        assertEquals(score, Math.round(score * 100.0) / 100.0, 1e-9);
    }

    // ── Composantes ──────────────────────────────────────────────────────────

    @Test
    void densityScore_denseTextIsOne_airyTextIsLower() {
        assertEquals(1.0, calc.densityScore("abcdefghij"), 1e-9);     // 100% non-espace → capé à 1.0
        assertTrue(calc.densityScore("a b c d e f g h") < 0.95);      // aéré → < 1.0
    }

    @Test
    void lengthScore_piecewise() {
        assertEquals(0.1, calc.lengthScore(29), 1e-9);   // artefact
        assertEquals(0.1, calc.lengthScore(30), 1e-9);   // début de l'interpolation
        assertEquals(1.0, calc.lengthScore(100), 1e-9);  // borne basse de la plage idéale
        assertEquals(1.0, calc.lengthScore(800), 1e-9);  // dans la plage idéale
        assertEquals(0.8, calc.lengthScore(2000), 1e-9); // trop long
        double mid = calc.lengthScore(65);               // ~milieu de l'interpolation
        assertTrue(mid > 0.4 && mid < 0.6, "interpolation 30→100, obtenu: " + mid);
    }

    @Test
    void coherenceScore_components() {
        assertEquals(1.0, calc.coherenceScore("Hello world."), 1e-9); // majuscule + point + ratio prose
        assertEquals(0.3, calc.coherenceScore("hello world"), 1e-9);  // ratio seul (pas de maj, pas de ponct)
        assertEquals(0.7, calc.coherenceScore("Hello world"), 1e-9);  // majuscule + ratio, sans ponct
    }
}
