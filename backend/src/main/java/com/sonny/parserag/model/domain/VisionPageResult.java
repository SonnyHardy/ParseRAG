package com.sonny.parserag.model.domain;

import java.util.List;

/**
 * Résultat d'un fallback vision <strong>plein-page</strong> sur une page scannée / image-only
 * (issue #11) : le texte du corps reconstruit par le modèle et les éventuels tableaux de la page.
 *
 * @param page   page source (1-based)
 * @param text   texte du corps en ordre de lecture (hors en-têtes/pieds et hors tableaux) ; jamais {@code null}
 * @param tables tableaux extraits de la page (peut être vide) ; chacun porte {@code fallbackUsed=true}
 */
public record VisionPageResult(
        int page,
        String text,
        List<TableResult> tables
) {}
