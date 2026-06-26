package com.sonny.parserag.service.fallback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionBudgetTest {

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
}
