package com.sonny.parserag.service.fallback;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionBudgetTest {

    /**
     * Horloge pilotee : le budget temps se teste en avancant le temps, jamais en dormant - un test
     * qui attend vraiment est lent et devient instable sous charge.
     */
    private final AtomicLong now = new AtomicLong(0);

    private VisionBudget budget(int max, Duration remaining) {
        return new VisionBudget(max, remaining, now::get);
    }

    private void advance(Duration d) {
        now.addAndGet(d.toNanos());
    }

    @Test
    void consumesUpToMaxThenStops() {
        VisionBudget b = new VisionBudget(2);
        assertTrue(b.hasRemaining());
        assertTrue(b.tryConsume());
        assertTrue(b.tryConsume());
        assertEquals(2, b.used());
        assertFalse(b.hasRemaining());
        assertFalse(b.tryConsume(), "au-delà du cap : refus");
        assertEquals(2, b.used());
    }

    @Test
    void zeroBudgetConsumesNothing() {
        VisionBudget b = new VisionBudget(0);
        assertFalse(b.hasRemaining());
        assertFalse(b.tryConsume());
    }

    @Test
    void negativeMaxClampedToZero() {
        assertEquals(0, new VisionBudget(-5).max());
    }

    // ── Budget temps (issue #57) ──────────────────────────────────────────────────────────

    @Test
    void withoutADeadlineOnlyThePageCapApplies() {
        VisionBudget b = budget(2, null);
        advance(Duration.ofHours(3));

        assertTrue(b.tryConsume(), "sans delai, le temps ne borne rien");
        assertFalse(b.deadlineExceeded());
    }

    @Test
    void callsArePermittedUntilTheDeadlineIsReached() {
        VisionBudget b = budget(10, Duration.ofSeconds(30));

        advance(Duration.ofSeconds(29));
        assertTrue(b.tryConsume(), "avant l'echeance, le budget reste ouvert");

        advance(Duration.ofSeconds(2));
        assertFalse(b.tryConsume(), "apres l'echeance, plus aucun appel n'est lance");
        assertEquals(1, b.used(), "un appel refuse ne consomme pas de quota");
    }

    @Test
    void anExhaustedDeadlineIsDistinguishableFromAnExhaustedPageCap() {
        // Les deux causes n'appellent pas la meme reaction : un cap atteint est un reglage produit,
        // un delai depasse est un incident de latence chez le fournisseur.
        VisionBudget capped = budget(1, Duration.ofSeconds(30));
        capped.tryConsume();
        assertFalse(capped.tryConsume());
        assertFalse(capped.deadlineExceeded(), "cap en pages : ce n'est pas le delai");

        VisionBudget expired = budget(10, Duration.ofSeconds(30));
        advance(Duration.ofSeconds(31));
        assertFalse(expired.tryConsume());
        assertTrue(expired.deadlineExceeded());
    }

    @Test
    void hasRemainingReflectsTheDeadlineToo() {
        VisionBudget b = budget(10, Duration.ofSeconds(30));
        assertTrue(b.hasRemaining());

        advance(Duration.ofSeconds(31));
        assertFalse(b.hasRemaining(), "le budget est epuise par le temps, pas par le compte");
    }

    @Test
    void anAlreadyElapsedDeadlineStartsExhausted() {
        // Cas reel : l'extraction et le nettoyage ont deja consomme tout le delai. Mieux vaut sortir
        // le document en revue manuelle que d'entamer un appel qu'on sait deja hors delai.
        VisionBudget b = budget(10, Duration.ofSeconds(-1));

        assertFalse(b.hasRemaining());
        assertFalse(b.tryConsume());
        assertTrue(b.deadlineExceeded());
    }
}
