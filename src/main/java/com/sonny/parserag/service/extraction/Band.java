package com.sonny.parserag.service.extraction;

import java.util.List;

/**
 * Tranche horizontale d'une page ayant une structure de colonnes homogène (issue #31).
 *
 * <p>Une page d'article n'a pas « un » nombre de colonnes : c'est un empilement de zones — bandeau
 * titre pleine largeur, puis deux colonnes, puis une légende de figure pleine largeur, puis deux
 * colonnes. Chaque bande capture une de ces zones avec ses propres {@code splits}.
 *
 * <p>La bande porte ses fragments plutôt qu'une plage Y : l'assemblage a ainsi la garantie que
 * chaque fragment de la page appartient à exactement une bande, sans risque de trou ni de
 * double-comptage aux frontières.
 *
 * <p>Package-privé : strictement interne au package {@code service.extraction}, comme
 * {@link Fragment}.
 *
 * @param fragments fragments de la bande, dans l'ordre où ils ont été capturés
 * @param splits    coordonnées X séparant les colonnes de <em>cette</em> bande ;
 *                  vide = bande mono-colonne. {@code splits.length + 1} = nombre de colonnes.
 */
record Band(List<Fragment> fragments, float[] splits) {

    /** Nombre de baselines distinctes (au point près) — la « hauteur » utile de la bande. */
    int rows() {
        return (int) fragments.stream().mapToInt(f -> Math.round(f.y())).distinct().count();
    }

    int columns() {
        return splits.length + 1;
    }
}
