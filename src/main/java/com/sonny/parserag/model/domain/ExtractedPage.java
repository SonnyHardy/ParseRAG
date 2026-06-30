package com.sonny.parserag.model.domain;

/**
 * Page extraite : texte reconstruit + métadonnées géométriques.
 *
 * @param readingOrderScore confiance d'ordre de lecture de la page ∈ [0, 1] (issue #30, palier 2) :
 *        proche de 1 si les lignes ont été lues dans le bon ordre, basse si l'assemblage a entrelacé
 *        des colonnes (alternance anormale des X de début de ligne). Vaut 1.0 quand l'information
 *        géométrique n'est pas disponible (fallback vision, pages sans texte natif).
 */
public record ExtractedPage(
        int pageNumber,
        String rawText,
        boolean hasImages,
        boolean likelyHasTable,
        double readingOrderScore
) {

    /** Constructeur de commodité : score d'ordre de lecture par défaut (1.0, neutre). */
    public ExtractedPage(int pageNumber, String rawText, boolean hasImages, boolean likelyHasTable) {
        this(pageNumber, rawText, hasImages, likelyHasTable, 1.0);
    }
}
