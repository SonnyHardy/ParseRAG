package com.sonny.parserag.service.pipeline;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.exception.ParseRagException;
import com.sonny.parserag.observability.ParseRagMetrics;
import com.sonny.parserag.observability.TestMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Borne de concurrence du parsing (issue #56).
 * <p>
 * Ce qui est vérifié n'est pas « ça compte bien jusqu'à N », mais le comportement sous saturation :
 * un refus franc et immédiat, plutôt qu'une file d'attente que personne ne voit — c'est cette
 * file-là qui, sans borne, remplissait les 200 threads de Tomcat et le tas avec eux.
 */
class ParseConcurrencyLimiterTest {

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final ParseRagMetrics metrics = TestMetrics.metrics(meters);

    private ParseConcurrencyLimiter limiter(int maxConcurrent, int maxWaitSeconds) {
        AppProperties props = new AppProperties();
        props.getParse().setMaxConcurrent(maxConcurrent);
        props.getParse().setMaxWaitSeconds(maxWaitSeconds);
        return new ParseConcurrencyLimiter(props, metrics);
    }

    @Test
    void runsTheWorkAndReturnsItsValue() {
        assertEquals("parsed", limiter(2, 1).withSlot(() -> "parsed"));
    }

    @Test
    void releasesTheSlotEvenWhenTheWorkFails() {
        ParseConcurrencyLimiter limiter = limiter(1, 0);

        assertThrows(IllegalStateException.class, () -> limiter.withSlot(() -> {
            throw new IllegalStateException("parse cassé");
        }));

        // Sans le finally, une seule exception condamnerait définitivement la place.
        assertEquals("ok", limiter.withSlot(() -> "ok"));
    }

    @Test
    void refusesWithServiceBusyWhenEveryStallIsTaken() throws Exception {
        ParseConcurrencyLimiter limiter = limiter(1, 0);
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder = new Thread(() -> limiter.withSlot(() -> {
            occupied.countDown();
            await(release);
            return "held";
        }));
        holder.start();
        assertTrue(occupied.await(5, TimeUnit.SECONDS), "la place doit être prise");

        ParseRagException rejected = assertThrows(ParseRagException.class,
                () -> limiter.withSlot(() -> "jamais exécuté"));

        assertEquals("SERVICE_BUSY", rejected.getErrorCode());
        assertEquals(503, rejected.getStatus().value());
        assertEquals(1, meters.get(ParseRagMetrics.PARSE_REJECTED_BUSY).counter().count());

        release.countDown();
        holder.join(5_000);
    }

    @Test
    void neverRunsMoreWorkThanTheConfiguredLimit() throws Exception {
        int limit = 2;
        ParseConcurrencyLimiter limiter = limiter(limit, 5);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(8);

        for (int i = 0; i < 8; i++) {
            new Thread(() -> {
                try {
                    limiter.withSlot(() -> {
                        peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                        sleep();
                        inFlight.decrementAndGet();
                        return null;
                    });
                } catch (ParseRagException ignored) {
                    // Refus admissible : ce test borne le pic, pas le taux de succès.
                } finally {
                    done.countDown();
                }
            }).start();
        }

        assertTrue(done.await(30, TimeUnit.SECONDS), "les threads doivent terminer");
        assertTrue(peak.get() <= limit, "pic observé : " + peak.get() + ", borne : " + limit);
    }

    @Test
    void freeSlotsAreExposedAsAGauge() {
        limiter(4, 1);
        assertEquals(4, meters.get(ParseRagMetrics.PARSE_SLOTS_FREE).gauge().value());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
