package com.sonny.parserag.service.health;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.model.response.HealthResponse;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests de {@link HealthService} (issue #37) : traduction des trois sondes ({@code db},
 * {@code flyway}, {@code disk}) en {@code UP}/{@code DOWN}, agrégation en statut global, et
 * comportement sur base qui ne répond pas (la borne de temps doit rendre la main).
 * <p>
 * Aucune DB ni contexte Spring : {@link JdbcTemplate} et {@link Flyway} sont mockés, la sonde
 * disque pointe sur un répertoire temporaire.
 */
class HealthServiceTest {

    @TempDir
    static Path tempDir;

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static AppProperties properties(int pingTimeoutSeconds) {
        return properties(pingTimeoutSeconds, tempDir.toString(), 1);
    }

    private static AppProperties properties(int pingTimeoutSeconds, String diskPath, long minFreeDiskMb) {
        AppProperties props = new AppProperties();
        props.getHealth().setDbPingTimeoutSeconds(pingTimeoutSeconds);
        props.getHealth().setDiskPath(diskPath);
        props.getHealth().setMinFreeDiskMb(minFreeDiskMb);
        props.getInfo().setName("ParseRAG");
        props.getInfo().setVersion("0.0.1-SNAPSHOT");
        return props;
    }

    private static JdbcTemplate reachableDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        return jdbc;
    }

    /** Base qui accepte la connexion mais ne répond jamais (cas Hikari bloqué sur 60 s). */
    private static JdbcTemplate hangingDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenAnswer(invocation -> {
            Thread.sleep(60_000);
            return 1;
        });
        return jdbc;
    }

    private static MigrationInfo migration(MigrationState state) {
        MigrationInfo migration = mock(MigrationInfo.class);
        when(migration.getState()).thenReturn(state);
        return migration;
    }

    private static Flyway flywayWith(MigrationInfo[] pending, MigrationInfo[] all) {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);
        when(info.pending()).thenReturn(pending);
        when(info.all()).thenReturn(all);
        return flyway;
    }

    /** Schéma à jour : rien en attente, tout appliqué avec succès. */
    private static Flyway migratedFlyway() {
        return flywayWith(new MigrationInfo[0], new MigrationInfo[]{migration(MigrationState.SUCCESS)});
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Flyway> provider(Flyway flyway) {
        ObjectProvider<Flyway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(flyway);
        return provider;
    }

    // ── Tout va bien ─────────────────────────────────────────────────────────

    @Test
    void allComponentsHealthy_reportsEverythingUp() {
        HealthResponse health =
                new HealthService(reachableDatabase(), provider(migratedFlyway()), properties(3)).check();

        assertEquals("UP", health.status());
        assertEquals("UP", health.db());
        assertEquals("UP", health.flyway());
        assertEquals("UP", health.disk());
        assertTrue(health.isUp());
    }

    @Test
    void allComponentsHealthy_carriesApplicationIdentityAndUtcTimestamp() {
        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        HealthResponse health =
                new HealthService(reachableDatabase(), provider(migratedFlyway()), properties(3)).check();
        Instant after = Instant.now();

        assertEquals("ParseRAG", health.application());
        assertEquals("0.0.1-SNAPSHOT", health.version());
        assertFalse(health.timestamp().isBefore(before), "timestamp antérieur à l'appel");
        assertFalse(health.timestamp().isAfter(after), "timestamp postérieur à l'appel");
    }

    // ── Base en panne ────────────────────────────────────────────────────────

    /** Sans base, l'état des migrations est indéterminé : DOWN sans même interroger Flyway. */
    @Test
    void databaseThrows_reportsDownAndDoesNotProbeFlyway() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));
        Flyway flyway = migratedFlyway();

        HealthResponse health = new HealthService(jdbc, provider(flyway), properties(3)).check();

        assertEquals("DOWN", health.status());
        assertEquals("DOWN", health.db());
        assertEquals("DOWN", health.flyway());
        assertFalse(health.isUp());
        verify(flyway, never()).info();
    }

    /**
     * Cas critique : une base qui ne répond pas (acquisition de connexion bloquée jusqu'au
     * {@code connection-timeout} Hikari, 60 s). Le health check doit rendre la main sur sa propre
     * borne, pas attendre la DB.
     */
    @Test
    void databaseHangs_timesOutAndReportsDown() {
        long startedAt = System.currentTimeMillis();
        HealthResponse health =
                new HealthService(hangingDatabase(), provider(migratedFlyway()), properties(1)).check();
        long elapsedMs = System.currentTimeMillis() - startedAt;

        assertEquals("DOWN", health.db());
        assertEquals("DOWN", health.status());
        assertTrue(elapsedMs < 10_000,
                "le health check a attendu la DB (%d ms) au lieu de sa borne".formatted(elapsedMs));
    }

    /** Un ping bloqué ne doit pas empoisonner les appels suivants : ils expirent, sans attendre. */
    @Test
    void databaseHangs_subsequentCallsStillReturnQuickly() {
        HealthService service =
                new HealthService(hangingDatabase(), provider(migratedFlyway()), properties(1));
        service.check();

        long startedAt = System.currentTimeMillis();
        HealthResponse health = service.check();
        long elapsedMs = System.currentTimeMillis() - startedAt;

        assertEquals("DOWN", health.db());
        assertTrue(elapsedMs < 10_000,
                "le 2e health check a attendu (%d ms)".formatted(elapsedMs));
    }

    // ── Schéma (Flyway) ──────────────────────────────────────────────────────

    /** Jar plus récent que la base (ou Flyway désactivé au déploiement) : le schéma est désaligné. */
    @Test
    void pendingMigration_reportsFlywayDown() {
        Flyway flyway = flywayWith(
                new MigrationInfo[]{migration(MigrationState.PENDING)},
                new MigrationInfo[]{migration(MigrationState.SUCCESS), migration(MigrationState.PENDING)});

        HealthResponse health =
                new HealthService(reachableDatabase(), provider(flyway), properties(3)).check();

        assertEquals("DOWN", health.flyway());
        assertEquals("DOWN", health.status(), "un composant DOWN fait tomber le statut global");
        assertEquals("UP", health.db(), "la base répond : seul le schéma est en cause");
    }

    /** Migration interrompue en cours de route : la base est joignable mais le schéma est cassé. */
    @Test
    void failedMigration_reportsFlywayDown() {
        Flyway flyway = flywayWith(
                new MigrationInfo[0],
                new MigrationInfo[]{migration(MigrationState.SUCCESS), migration(MigrationState.FAILED)});

        HealthResponse health =
                new HealthService(reachableDatabase(), provider(flyway), properties(3)).check();

        assertEquals("DOWN", health.flyway());
        assertEquals("DOWN", health.status());
        assertEquals("UP", health.db());
    }

    /** {@code spring.flyway.enabled=false} : pas de bean, rien à vérifier — pas de faux DOWN. */
    @Test
    void noFlywayBean_reportsFlywayUp() {
        HealthResponse health =
                new HealthService(reachableDatabase(), provider(null), properties(3)).check();

        assertEquals("UP", health.flyway());
        assertEquals("UP", health.status());
    }

    // ── Disque ───────────────────────────────────────────────────────────────

    @Test
    void diskBelowThreshold_reportsDiskDown() {
        // Plancher volontairement inatteignable (≈ 1 Po) : aucun volume de test ne le satisfait.
        AppProperties props = properties(3, tempDir.toString(), 1_000_000_000L);

        HealthResponse health =
                new HealthService(reachableDatabase(), provider(migratedFlyway()), props).check();

        assertEquals("DOWN", health.disk());
        assertEquals("DOWN", health.status());
        assertEquals("UP", health.db(), "la base répond : seul le disque est en cause");
    }

    /** Chemin mal configuré : {@code getUsableSpace()} rendrait 0, on refuse de le lire comme sain. */
    @Test
    void missingDiskPath_reportsDiskDown() {
        AppProperties props = properties(3, tempDir.resolve("volume-inexistant").toString(), 1);

        HealthResponse health =
                new HealthService(reachableDatabase(), provider(migratedFlyway()), props).check();

        assertEquals("DOWN", health.disk());
        assertEquals("DOWN", health.status());
    }
}
