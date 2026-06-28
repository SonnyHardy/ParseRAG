package com.sonny.parserag.service.processing;

import com.sonny.parserag.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calcule un <strong>reading-order confidence</strong> ∈ [0, 1] pour un chunk de prose
 * (issue #30, palier 1). Contrairement à la v1 (issue #8) qui ne notait que la <em>forme</em>
 * du texte (densité/longueur/cohérence) — et donnait donc 0.9 à un chunk pourtant entrelacé —,
 * ce score capte d'abord la trace d'un <strong>désordre de lecture</strong> (colonnes recollées
 * dans le mauvais ordre), seul défaut qui compte vraiment pour du RAG.
 *
 * <p>Combinaison <strong>multiplicative</strong> : une pénalité d'ordre de lecture module le
 * score « forme », de sorte qu'un chunk dense, long et bien ponctué mais <em>entrelacé</em> ne
 * peut plus hériter d'un score élevé :
 *
 * <pre>
 *   confidence = readingOrder · density · (0.5 · length + 0.5 · coherence)
 * </pre>
 *
 * <ul>
 *   <li><strong>readingOrder</strong> — pénalise les traces textuelles d'un entrelacement de
 *       colonnes : <em>(A)</em> espaces internes larges (≥ {@link #INTERIOR_SPACE_MIN} espaces en
 *       milieu de ligne, réinjectés par l'extracteur quand deux fragments de colonnes différentes
 *       partagent une baseline) et <em>(B)</em> césures suivies d'un espace (« apply- Abstract »,
 *       « down- We » : une césure normale est suivie d'un retour ligne et d'une minuscule, pas
 *       d'un espace + mot). La pénalité décroît avec le taux d'anomalies/ligne, jusqu'à un
 *       plancher configurable.</li>
 *   <li><strong>density</strong> — proportion de caractères non-espace.</li>
 *   <li><strong>length</strong> — pénalise les fragments trop courts (artefacts) et,
 *       plus légèrement, les chunks trop longs.</li>
 *   <li><strong>coherence</strong> — majuscule initiale, ponctuation finale, et densité
 *       lexicale typique de la prose.</li>
 * </ul>
 *
 * <p><strong>Portée assumée</strong> : ce score mesure l'ordre de lecture, pas la qualité
 * linguistique absolue. Il ne dira rien d'un caractère OCR mal reconnu. N'est appelé que sur les
 * chunks {@code PARAGRAPH} (les tableaux / fallback vision ont leur propre confiance). Résultat
 * arrondi à 2 décimales.
 */
@Service
@RequiredArgsConstructor
public class ConfidenceCalculatorService {

    private final AppProperties appProperties;

    /** Poids internes de la moyenne longueur/cohérence, ensuite modulée par la densité. */
    private static final double WEIGHT_LENGTH    = 0.5;
    private static final double WEIGHT_COHERENCE = 0.5;

    /** Densité cible : au-delà de 60% de caractères non-espace, la prose est jugée pleinement dense. */
    private static final double DENSITY_TARGET_RATIO = 0.6;

    private static final int LENGTH_MIN_GOOD = 100;
    private static final int LENGTH_MAX_GOOD = 1500;
    private static final int LENGTH_ARTIFACT = 30;

    /** Bornes du ratio mots/caractères typique de la prose normale. */
    private static final double WORD_RATIO_MIN = 0.12;
    private static final double WORD_RATIO_MAX = 0.25;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // ── Signaux d'ordre de lecture (palier 1) ──────────────────────────────────────────
    /** Nombre minimal d'espaces consécutifs en milieu de ligne considéré comme trace d'entrelacement. */
    private static final int INTERIOR_SPACE_MIN = 3;
    /** (A) Un vide horizontal large bordé de texte sur une même ligne (colonnes recollées). */
    private static final Pattern INTERIOR_SPACE_RUN = Pattern.compile("\\S {" + INTERIOR_SPACE_MIN + ",}\\S");
    /** (B) Lettre + trait d'union + espace : césure interrompue par un fragment d'une autre colonne. */
    private static final Pattern HYPHEN_THEN_SPACE  = Pattern.compile("\\p{L}-[ \\t]");

    /** Score de confiance ∈ [0, 1], arrondi à 2 décimales. */
    public double calculate(String text) {
        if (text == null) return 0.0;
        String t = text.strip();
        if (t.isEmpty()) return 0.0;

        double quality = WEIGHT_LENGTH    * lengthScore(t.length())
                       + WEIGHT_COHERENCE * coherenceScore(t);
        double confidence = readingOrderScore(t) * densityScore(t) * quality;

        return round2(clamp01(confidence));
    }

    /**
     * Pénalité ∈ [floor, 1] reflétant l'ordre de lecture : 1.0 si aucune anomalie, décroissant
     * linéairement avec le taux d'anomalies/ligne jusqu'au plancher {@code penalty-floor}.
     */
    double readingOrderScore(String t) {
        int anomalies = count(INTERIOR_SPACE_RUN, t) + count(HYPHEN_THEN_SPACE, t);
        if (anomalies == 0) return 1.0;

        int lines = 1 + (int) t.chars().filter(c -> c == '\n').count();
        double rate = (double) anomalies / lines;

        double penalty = 1.0 - rate / appProperties.getConfidence().getMaxAnomalyRate();
        return Math.max(appProperties.getConfidence().getPenaltyFloor(), penalty);
    }

    /** Proportion de caractères non-espace, normalisée par {@link #DENSITY_TARGET_RATIO}. */
    double densityScore(String t) {
        int nonSpace = 0;
        for (int i = 0; i < t.length(); i++) {
            if (!Character.isWhitespace(t.charAt(i))) nonSpace++;
        }
        double ratio = (double) nonSpace / t.length();
        return Math.min(1.0, ratio / DENSITY_TARGET_RATIO);
    }

    /**
     * 1.0 sur la plage idéale [100, 1500], 0.1 sous 30 (artefact probable), interpolation
     * linéaire entre les deux, et 0.8 au-delà de 1500 (chunk trop long, moins fiable).
     */
    double lengthScore(int length) {
        if (length < LENGTH_ARTIFACT) return 0.1;
        if (length < LENGTH_MIN_GOOD) {
            return 0.1 + (double) (length - LENGTH_ARTIFACT) / (LENGTH_MIN_GOOD - LENGTH_ARTIFACT) * 0.9;
        }
        if (length <= LENGTH_MAX_GOOD) return 1.0;
        return 0.8;
    }

    /** Somme de trois indices de « bonne forme » : majuscule initiale, ponctuation finale, ratio lexical. */
    double coherenceScore(String t) {
        double score = 0.0;
        if (Character.isUpperCase(t.charAt(0))) score += 0.4;

        char last = t.charAt(t.length() - 1);
        if (last == '.' || last == '?' || last == '!' || last == ':') score += 0.3;

        int words = WHITESPACE.split(t).length;
        double wordRatio = (double) words / t.length();
        if (wordRatio >= WORD_RATIO_MIN && wordRatio <= WORD_RATIO_MAX) score += 0.3;

        return score;
    }

    /** Nombre d'occurrences (non chevauchantes) du motif dans le texte. */
    private int count(Pattern p, String t) {
        Matcher m = p.matcher(t);
        int c = 0;
        while (m.find()) c++;
        return c;
    }

    private double clamp01(double v) {
        return Math.clamp(v, 0.0, 1.0);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
