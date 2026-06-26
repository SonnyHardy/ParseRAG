package com.sonny.parserag.service.fallback;

/**
 * Compteur d'appels vision <strong>partagé par document</strong> (issue #11) : plafonne le nombre
 * total d'images envoyées à OpenAI sur un même document, toutes sources confondues — régions de
 * tableau ({@code TableExtractorService}) <em>et</em> pages scannées
 * ({@code ScannedDocumentFallbackService}).
 *
 * <p>Instancié par requête dans le pipeline et passé aux deux consommateurs : un seul cap
 * ({@code vision.max-pages-per-document}) gouverne l'ensemble. Non thread-safe — un document est
 * traité séquentiellement.
 */
public final class VisionBudget {

    private final int max;
    private int used;

    public VisionBudget(int max) {
        this.max = Math.max(0, max);
    }

    /** Reste-t-il au moins un appel vision disponible ? */
    public boolean hasRemaining() {
        return used < max;
    }

    /** Consomme un appel si le budget le permet ; renvoie {@code true} si consommé. */
    public boolean tryConsume() {
        if (used < max) {
            used++;
            return true;
        }
        return false;
    }

    public int used() {
        return used;
    }

    public int max() {
        return max;
    }
}
