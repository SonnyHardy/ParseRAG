package com.sonny.parserag.service.processing;

import com.sonny.parserag.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du reading-order confidence (issues #8 puis #30, palier 1). La formule est
 * {@code readingOrder · density · (0.5·length + 0.5·coherence)} : un chunk propre score haut,
 * un fragment/continuation score plus bas, et — nouveauté #30 — un chunk <em>entrelacé</em>
 * (colonnes recollées dans le désordre) est pénalisé là où la v1 le laissait à 0.9.
 */
class ConfidenceCalculatorServiceTest {

    private final ConfidenceCalculatorService calc = build();

    /** POJO sans contexte Spring : on injecte une {@link AppProperties} avec les seuils #30. */
    private static ConfidenceCalculatorService build() {
        AppProperties props = new AppProperties();
        props.getConfidence().setMaxAnomalyRate(0.2);
        props.getConfidence().setPenaltyFloor(0.3);
        return new ConfidenceCalculatorService(props);
    }

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
        // socle 0.3 + ratio prose 0.3 + majuscule 0.2 + ponctuation 0.2
        assertEquals(1.0, calc.coherenceScore("Hello world."), 1e-9); // tout présent
        assertEquals(0.6, calc.coherenceScore("hello world"), 1e-9);  // socle + ratio (continuation : pas de maj/ponct)
        assertEquals(0.8, calc.coherenceScore("Hello world"), 1e-9);  // socle + ratio + majuscule, sans ponct
    }

    @Test
    void readingOrder_ignoresJustifiedGapsAndHeadings_flagsBrokenHyphenation() {
        // Texte justifié / titre numéroté : les vides internes larges ne sont PLUS pénalisés
        // (ils confondaient justification et entrelacement — faux positifs légaux/allemands).
        assertEquals(1.0, calc.readingOrderScore("1.   Le traitement des données à caractère personnel"), 1e-9);
        assertEquals(1.0, calc.readingOrderScore("Universität   Paderborn:   Studie   zur   Auswirkung"), 1e-9);
        assertEquals(1.0, calc.readingOrderScore("4.1   Funktionen zur Reduzierung von Ablenkung"), 1e-9);
        // Mais la césure-espace (recollage de colonnes) reste pénalisée.
        assertTrue(calc.readingOrderScore("strategies for apply- Abstract\ning pre-trained to down- We") < 0.6);
    }

    // ── Reading-order (issue #30, palier 1) ────────────────────────────────────

    @Test
    void cleanProse_keepsFullReadingOrder() {
        String clean = "This is a perfectly normal paragraph of prose. "
                + "It has state-of-the-art hyphenated words and ends well.";
        // Aucune anomalie (pas d'espace interne large, pas de césure-espace) → pas de pénalité.
        assertEquals(1.0, calc.readingOrderScore(clean), 1e-9);
    }

    @Test
    void interleavedText_isClampedToFloor() {
        String interleaved = "alpha beta gamma for apply- Abstract\n"
                + "foo bar Rad-      include the\n"
                + "baz qux task-      they use";
        // 5 anomalies / 3 lignes → pénalité négative, ramenée au plancher (0.3).
        assertEquals(0.3, calc.readingOrderScore(interleaved), 1e-9);
    }

    @Test
    void pageReadingOrder_modulatesConfidenceMultiplicatively() {
        String clean = "This is a perfectly clean paragraph that reads in order and ends well.";
        double full   = calc.calculate(clean, 1.0);   // page lue dans le bon ordre
        double penal  = calc.calculate(clean, 0.3);   // page entrelacée (palier 2)
        assertTrue(penal < full, "un score de page bas doit tirer la confidence vers le bas");
        // Modulation multiplicative : ~0.3× le score pleine page (à l'arrondi près).
        assertEquals(0.3 * full, penal, 0.01);
    }

    @Test
    void pivot_interleavedChunkScoresBelowCleanChunk() {
        double interleaved = calc.calculate(CHUNK_000_INTERLEAVED);
        double clean       = calc.calculate(CHUNK_007_CLEAN);

        // Le chunk propre garde son ordre de lecture intact...
        assertEquals(1.0, calc.readingOrderScore(CHUNK_007_CLEAN), 1e-9);
        // ...l'entrelacé est nettement pénalisé...
        assertTrue(calc.readingOrderScore(CHUNK_000_INTERLEAVED) < 0.6,
                "le chunk entrelacé devrait être pénalisé sur l'ordre de lecture");
        // ...et au final il passe SOUS le chunk propre (v1 : les deux à 0.9).
        assertTrue(interleaved < clean,
                "pivot #30 : entrelacé (" + interleaved + ") doit être < propre (" + clean + ")");
    }

    /** chunk_000 réel (BERT arxiv-1810.04805) : abstract/intro à deux colonnes recollées dans le désordre. */
    private static final String CHUNK_000_INTERLEAVED = """
            BERT: Pre-training of Deep Bidirectional Transformers for
            Language Understanding
            Jacob Devlin   Ming-Wei Chang   Kenton Lee   Kristina Toutanova
            Google AI Language
            There are two existing strategies for apply- Abstract
            ing pre-trained language representations to down-
            We introduce a new language representa-
            stream tasks: feature-based and fine-tuning. The
            tion model called BERT, which stands for
            feature-based approach, such as ELMo (Peters
            Bidirectional Encoder Representations from
            et al., 2018a), uses task-specific architectures that
            Transformers. Unlike recent language repre-
            sentation models (Peters et al., 2018a; Rad-      include the pre-trained representations as addi-
            ford et al., 2018), BERT is designed to pre-
            tional features. The fine-tuning approach, such as
            train deep bidirectional representations from
            the Generative Pre-trained Transformer (OpenAI
            unlabeled text by jointly conditioning on both
            left and right context in all layers. As a re-
            sult, the pre-trained BERT model can be fine-
            same objective function during pre-training, where range of tasks, such as question answering and
            language inference, without substantial task-      they use unidirectional language models to learn
            specific architecture modifications.
            general language representations.
            BERT is conceptually simple and empirically      We argue that current techniques restrict the
            powerful.  It obtains new state-of-the-art re-
            sults on eleven natural language processing
            cially for the fine-tuning approaches.""";

    /** chunk_007 réel (BERT) : page mono-colonne lue proprement, ordre de lecture intact. */
    private static final String CHUNK_007_CLEAN = """
            Throughout this work, a "sentence" can be an arbi-
            trary span of contiguous text, rather than an actual
            linguistic sentence. A "sequence" refers to the in-
            put token sequence to BERT, which may be a sin-
            gle sentence or two sentences packed together.
            We use WordPiece embeddings (Wu et al.,
            2016) with a 30,000 token vocabulary. The first
            token of every sequence is always a special clas-
            sification token ([CLS]). The final hidden state
            corresponding to this token is used as the ag-
            gregate sequence representation for classification
            tasks. Sentence pairs are packed together into a
            single sequence. We differentiate the sentences in
            two ways. First, we separate them with a special
            token ([SEP]). Second, we add a learned embed-
            ding to every token indicating whether it belongs
            to sentence A or sentence B. As shown in Figure 1,
            we denote input embedding as E, the final hidden
            vector of the special token, and the final hidden
            vector for the ith input token. For a given token,
            its input representation is constructed by summing
            the corresponding token, segment, and position.""";
}
