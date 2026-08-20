package com.sonny.parserag.service.fallback;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Budget vision <strong>partagé par document</strong> — en nombre d'appels (issue #11) et en
 * <strong>temps</strong> (issue #57). Il gouverne les deux consommateurs : régions de tableau
 * ({@code TableExtractorService}) et pages scannées ({@code ScannedDocumentFallbackService}).
 *
 * <p><strong>Pourquoi un budget temps.</strong> Le coût d'un parse n'est pas proportionnel au
 * nombre de pages — 244 pages de texte natif prennent 7 s — mais à la queue de latence du
 * fournisseur de vision. Mesuré : un document de 16 pages, habituellement traité en 9 à 13 s, a
 * pris <strong>702 s</strong> lors d'un décrochage du fournisseur. La configuration laissait cette
 * queue non bornée : {@code timeout-ms} × {@code max-retries} × {@code max-pages-per-document}
 * autorisait plus d'une heure sur un seul document, très au-delà du couperet de 180 s du proxy
 * RapidAPI — qui aurait rendu un 504 vide après trois minutes d'attente.
 *
 * <p><strong>Ce que la borne garantit, et ce qu'elle ne garantit pas.</strong> Passé le délai,
 * aucun <em>nouvel</em> appel n'est lancé ; un appel déjà en vol n'est pas interrompu, c'est le
 * {@code timeout} par appel qui le borne. Le pire cas total vaut donc
 * {@code deadline + (max-retries + 1) × timeout + backoff}, et c'est cette somme qui doit rester
 * sous le couperet du proxy.
 *
 * <p><strong>Ce qui arrive au document.</strong> Rien de spécial : l'épuisement du budget suit le
 * chemin qui existait déjà pour le cap en pages — le fallback rend {@code null}, l'appelant marque
 * {@code manual_review_needed}. Le client reçoit un <strong>200 partiel et exploitable</strong> au
 * lieu d'un 504 sans corps. La dégradation gracieuse n'a pas été inventée ici, on lui a seulement
 * donné un second déclencheur.
 *
 * <p>Non thread-safe : un document est traité séquentiellement.
 */
public final class VisionBudget {

    /** Horloge monotone : {@code nanoTime} ne recule pas si l'heure système est ajustée. */
    private final LongSupplier clock;
    private final int max;
    private final long deadlineNanos;

    private int used;
    private boolean deadlineHit;

    /** Budget sans borne de temps — seul le cap en pages s'applique. */
    public VisionBudget(int max) {
        this(max, null, System::nanoTime);
    }

    /**
     * @param remaining temps restant pour l'ensemble des appels vision du document, ou {@code null}
     *                  pour n'appliquer que le cap en pages. Une durée nulle ou négative épuise
     *                  immédiatement le budget : le document sort entièrement en revue manuelle
     *                  plutôt que d'entamer un appel qu'on sait déjà hors délai.
     */
    public VisionBudget(int max, Duration remaining) {
        this(max, remaining, System::nanoTime);
    }

    /** Variante testable : l'horloge est injectée pour ne pas faire dépendre un test du temps réel. */
    VisionBudget(int max, Duration remaining, LongSupplier clock) {
        this.clock = clock;
        this.max = Math.max(0, max);
        this.deadlineNanos = remaining == null
                ? Long.MAX_VALUE
                : clock.getAsLong() + remaining.toNanos();
    }

    /** Reste-t-il un appel disponible, en nombre comme en temps ? */
    public boolean hasRemaining() {
        return used < max && !outOfTime();
    }

    /** Consomme un appel si le budget le permet ; renvoie {@code true} si consommé. */
    public boolean tryConsume() {
        if (outOfTime()) {
            deadlineHit = true;
            return false;
        }
        if (used < max) {
            used++;
            return true;
        }
        return false;
    }

    /**
     * Le délai a-t-il été atteint au point de refuser au moins un appel ? Distinct de
     * {@link #hasRemaining()} : sert à relever la cause de l'épuisement, un cap en pages et un
     * dépassement de délai n'appelant pas la même réaction côté exploitation.
     */
    public boolean deadlineExceeded() {
        return deadlineHit;
    }

    public int used() {
        return used;
    }

    public int max() {
        return max;
    }

    private boolean outOfTime() {
        if (deadlineNanos == Long.MAX_VALUE) {
            return false;
        }
        // Soustraction plutôt que comparaison directe : elle reste correcte au débordement de
        // nanoTime, dont l'origine est arbitraire.
        boolean expired = clock.getAsLong() - deadlineNanos >= 0;
        if (expired) {
            deadlineHit = true;
        }
        return expired;
    }
}
