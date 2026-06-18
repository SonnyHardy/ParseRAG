package com.sonny.parserag.service.processing;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Calcule un score de confiance ∈ [0, 1] pour un chunk, reflétant la fiabilité de
 * l'extraction (issue #8). Heuristique v1 reposant sur trois signaux, chacun étant un
 * proxy de « ceci ressemble à du texte propre, bien extrait ».
 *
 * <p>Combinaison <strong>multiplicative</strong> : la densité module la qualité moyenne
 * (longueur + cohérence), de sorte qu'un seul signal faible tire réellement le score vers
 * le bas (un artefact court ou aéré ne peut pas hériter d'un plancher élevé) :
 *
 * <pre>
 *   confidence = density · (0.5 · length + 0.5 · coherence)
 * </pre>
 *
 * <ul>
 *   <li><strong>density</strong> — proportion de caractères non-espace. Un texte aéré
 *       (tableau mal parsé, colonnes mal recollées) a beaucoup d'espaces → score bas.</li>
 *   <li><strong>length</strong> — pénalise les fragments trop courts (artefacts) et,
 *       plus légèrement, les chunks trop longs (moins fiables).</li>
 *   <li><strong>coherence</strong> — majuscule initiale, ponctuation finale, et densité
 *       lexicale typique de la prose : signale un passage bien formé.</li>
 * </ul>
 *
 * Résultat arrondi à 2 décimales.
 */
@Service
public class ConfidenceCalculatorService {

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

    /** Score de confiance ∈ [0, 1], arrondi à 2 décimales. */
    public double calculate(String text) {
        if (text == null) return 0.0;
        String t = text.strip();
        if (t.isEmpty()) return 0.0;

        double quality = WEIGHT_LENGTH    * lengthScore(t.length())
                       + WEIGHT_COHERENCE * coherenceScore(t);
        double confidence = densityScore(t) * quality;

        return round2(clamp01(confidence));
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

    private double clamp01(double v) {
        return Math.clamp(v, 0.0, 1.0);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
