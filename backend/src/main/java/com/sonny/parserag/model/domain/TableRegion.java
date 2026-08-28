package com.sonny.parserag.model.domain;

/**
 * Région d'une page identifiée comme contenant un tableau (issue #9).
 *
 * <p>Sortie du {@code TableRegionDetector} : c'est un <em>localisateur</em>, pas un
 * extracteur. Le flag {@link #bordered()} porte la ligne de partage du routage aval :
 * <ul>
 *   <li>{@code bordered=true} → tableau à filets, extractible par Tabula spreadsheet ;</li>
 *   <li>{@code bordered=false} → tableau sans bordures (ex. booktabs/LaTeX), ancré par
 *       sa légende « Table N » et destiné à l'extraction ciblée / fallback vision.</li>
 * </ul>
 *
 * @param page         page source (1-based)
 * @param x0           bord gauche du corps du tableau (pts, repère YDirAdj, origine haut-gauche)
 * @param y0           bord haut
 * @param x1           bord droit
 * @param y1           bord bas
 * @param caption      légende associée (« Table 1: … ») ou {@code null}
 * @param bordered     vrai si détecté via ses filets (Tabula), faux si ancré par légende
 * @param columnIndex  colonne de page où vit le tableau (0 = gauche ; -1 si non pertinent)
 * @param rowLineCount nombre de lignes tabulaires retenues (debug + futur score qualité)
 */
public record TableRegion(
        int page,
        float x0,
        float y0,
        float x1,
        float y1,
        String caption,
        boolean bordered,
        int columnIndex,
        int rowLineCount
) {}
