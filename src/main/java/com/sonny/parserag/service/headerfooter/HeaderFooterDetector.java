package com.sonny.parserag.service.headerfooter;

import com.sonny.parserag.config.AppProperties;

import java.util.List;
import java.util.Set;

/**
 * Une couche de détection header/footer. Plusieurs couches s'empilent (cf.
 * {@link HeaderFooterCleaningService}) : chacune renvoie les blocs qu'elle juge être
 * des header/footer ; l'orchestrateur fait l'union des confirmations puis strippe.
 * <p>
 * L'ordre d'exécution est donné par {@link org.springframework.core.annotation.Order}.
 */
interface HeaderFooterDetector {

    /** Nom court pour les logs. */
    String name();

    /** Retourne les blocs jugés header/footer parmi {@link DetectionContext#blocks()}. */
    Set<TextBlock> detect(DetectionContext ctx);

    /**
     * Contexte passé à chaque couche.
     *
     * @param blocks           tous les blocs du document (mêmes objets d'une couche à l'autre)
     * @param pageCount        nombre de pages du document
     * @param documentId       identifiant du document (logs)
     * @param cfg              configuration header/footer
     * @param alreadyConfirmed blocs déjà confirmés par les couches précédentes (mutable, lecture seule conseillée)
     */
    record DetectionContext(List<TextBlock> blocks,
                            int pageCount,
                            String documentId,
                            AppProperties.HeaderFooterCleaning cfg,
                            Set<TextBlock> alreadyConfirmed) {}
}
