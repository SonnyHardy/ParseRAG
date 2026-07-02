package com.sonny.parserag.model.domain;

import java.util.Set;

/**
 * Page extraite : texte reconstruit + métadonnées géométriques.
 *
 * @param reorderSuspectLines lignes de la page (texte exact) marquées comme <strong>anomalies
 *        d'ordre de lecture</strong> (issue #30, palier 3) : lignes démarrant par un saut X arrière
 *        anormal lors d'un assemblage mono-colonne, symptôme d'un entrelacement de colonnes. Le
 *        chunking attribue ces lignes à chaque chunk pour un score d'ordre de lecture <em>par
 *        chunk</em> (un chunk propre sur une page par ailleurs entrelacée n'est plus pénalisé).
 *        Vide quand l'ordre de lecture est sain ou la géométrie indisponible (fallback vision).
 */
public record ExtractedPage(
        int pageNumber,
        String rawText,
        boolean hasImages,
        boolean likelyHasTable,
        Set<String> reorderSuspectLines
) {

    /** Constructeur de commodité : aucune ligne suspecte (ordre de lecture sain par défaut). */
    public ExtractedPage(int pageNumber, String rawText, boolean hasImages, boolean likelyHasTable) {
        this(pageNumber, rawText, hasImages, likelyHasTable, Set.of());
    }
}
