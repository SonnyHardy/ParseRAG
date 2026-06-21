package com.sonny.parserag.model.domain;

import java.util.List;

/**
 * Tableau extrait d'un PDF par Tabula-java (issue #9).
 *
 * @param page       page source (1-based)
 * @param caption    légende détectée (« Table 3: … ») ou {@code null}
 * @param headers    première ligne du tableau
 * @param rows       lignes de données (hors en-tête)
 * @param rowCount     nombre total de lignes (en-tête incluse)
 * @param colCount     nombre de colonnes
 * @param confidence   fiabilité de l'extraction ∈ [0, 1]
 * @param fallbackUsed vrai si le tableau a été reconstruit par le fallback vision (GPT-4o mini)
 */
public record TableResult(
        int page,
        String caption,
        List<String> headers,
        List<List<String>> rows,
        int rowCount,
        int colCount,
        double confidence,
        boolean fallbackUsed
) {}
