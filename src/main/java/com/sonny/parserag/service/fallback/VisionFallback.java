package com.sonny.parserag.service.fallback;

import com.sonny.parserag.model.domain.TableResult;
import com.sonny.parserag.model.domain.VisionPageResult;

/**
 * Port du fallback d'extraction par vision, sur deux usages :
 * <ul>
 *   <li>{@link #extractTable} — <strong>région de tableau</strong> sans bordures que Tabula
 *       reconstruit mal (issue #9) : image rognée → grille ;</li>
 *   <li>{@link #extractPage} — <strong>page scannée / image-only</strong> (issue #11) : image de la
 *       page entière → texte du corps + tableaux.</li>
 * </ul>
 *
 * <p>Deux implémentations coexistent, sélectionnées par {@code parserag.vision.provider}
 * (issue #28) : {@link GeminiVisionFallbackService} (défaut) et {@link OpenAiVisionFallbackService}.
 *
 * <p><strong>Contrat de dégradation gracieuse</strong> — commun aux implémentations et sur lequel
 * s'appuie tout l'aval ({@code manual_review_needed}) : aucune méthode ne lève d'exception. Vision
 * désactivée, clé absente, appel réseau en échec ou réponse inexploitable ⇒ {@code null}.
 */
public interface VisionFallback {

    /** Vrai si le fallback vision est activé <em>et</em> qu'une clé d'API est disponible. */
    boolean isAvailable();

    /**
     * Reconstruit un tableau à partir de l'image (PNG) d'une région. Renvoie {@code null} si la
     * vision est indisponible, si l'appel échoue, ou si la réponse ne contient pas de tableau
     * exploitable.
     */
    TableResult extractTable(byte[] regionPng, int page, String caption);

    /**
     * Reconstruit une page scannée entière (texte du corps + tableaux) à partir de son image (PNG).
     * Renvoie {@code null} si la vision est indisponible, si l'appel échoue, ou si la page ne
     * contient rien d'exploitable.
     */
    VisionPageResult extractPage(byte[] pagePng, int page);
}
