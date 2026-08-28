package com.sonny.parserag.service.health;

import com.sonny.parserag.observability.ParseRagMetrics;
import com.sonny.parserag.observability.TestMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ping de maintien en éveil de la base (issue #15).
 * <p>
 * Le comportement qui compte n'est pas le succès — une requête triviale qui passe n'apprend rien —
 * mais l'échec : il ne doit ni remonter, ni passer inaperçu.
 */
class DatabaseKeepAliveTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final ParseRagMetrics metrics = TestMetrics.metrics(meters);

    private final DatabaseKeepAlive keepAlive = new DatabaseKeepAlive(jdbcTemplate, metrics);

    @Test
    void pingsWithATrivialQuery() {
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);

        keepAlive.ping();

        // Une requête, pas une transaction : elle compte comme activité et ne verrouille rien.
        verify(jdbcTemplate).queryForObject("SELECT 1", Integer.class);
        assertEquals(1, counter("success"));
    }

    @Test
    void aFailedPingNeitherThrowsNorGoesUnnoticed() {
        // Relancer tuerait la planification pour de bon — fixedDelay ne replanifie pas après une
        // tâche en échec — et le composant cesserait silencieusement de protéger la base.
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("base injoignable"));

        assertDoesNotThrow(keepAlive::ping);

        assertEquals(1, counter("failure"),
                "un ping en échec doit être compté : c'est le seul témoin d'une panne silencieuse");
    }

    @Test
    void repeatedFailuresKeepBeingCounted() {
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("base injoignable"));

        keepAlive.ping();
        keepAlive.ping();
        keepAlive.ping();

        assertEquals(3, counter("failure"), "le composant continue de tenter après un échec");
    }

    private double counter(String outcome) {
        return meters.get(ParseRagMetrics.DB_KEEPALIVE).tag("outcome", outcome).counter().count();
    }
}
