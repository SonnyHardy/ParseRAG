package com.sonny.parserag.service.health;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.model.response.HealthResponse;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * État de santé de l'application (issue #37) : identité du build + sondes sur les dépendances
 * externes ({@code db}, {@code flyway}, {@code disk}).
 * <p>
 * Le périmètre est délibéré : on ne sonde que ce que l'application <em>ne peut pas garantir
 * elle-même</em>. Un « check applicatif » exécuté depuis l'application est une tautologie — s'il
 * répond, c'est que l'application est vivante. La mémoire/heap n'y figure pas non plus : son
 * occupation est une dent de scie (le GC ne passe qu'à saturation), un seuil y produirait des
 * {@code DOWN} sur une JVM parfaitement saine tout en ratant l'OOM réel, qui survient en quelques
 * secondes entre deux scrutations. C'est une métrique, pas un état binaire.
 * <p>
 * Les sondes qui passent par la base ({@code SELECT 1}, lecture de {@code flyway_schema_history})
 * sont exécutées sur un thread dédié et attendues au plus
 * {@code parserag.health.db-ping-timeout-seconds}. Cette borne est indispensable : l'acquisition
 * d'une connexion Hikari bloque jusqu'à {@code connection-timeout} quand Postgres est injoignable,
 * et ce délai est dimensionné pour le parsing, pas pour un health check. Passé la borne — comme sur
 * toute exception — le composant est déclaré {@code DOWN}.
 * <p>
 * L'exécuteur est mono-thread volontairement : si une sonde reste bloquée, les suivantes ne peuvent
 * pas démarrer et expirent immédiatement, ce qui est le diagnostic correct (base qui ne répond
 * pas) tout en bornant à un seul le nombre de threads immobilisés.
 */
@Slf4j
@Service
public class HealthService {

    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final JdbcTemplate jdbcTemplate;
    /** {@code ObjectProvider} : le bean Flyway n'existe pas si {@code spring.flyway.enabled=false}. */
    private final ObjectProvider<Flyway> flywayProvider;
    private final AppProperties properties;

    /** Thread daemon : ne doit jamais retenir l'arrêt de la JVM, même bloqué sur un socket DB. */
    private final ExecutorService pingExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "health-db-ping");
        thread.setDaemon(true);
        return thread;
    });

    public HealthService(JdbcTemplate jdbcTemplate,
                         ObjectProvider<Flyway> flywayProvider,
                         AppProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.flywayProvider = flywayProvider;
        this.properties = properties;
    }

    /**
     * Compose l'état courant. Le statut global est la conjonction des composants : un seul
     * {@code DOWN} suffit (le contrôleur traduit alors en HTTP 503).
     */
    public HealthResponse check() {
        String db = probeWithTimeout("ping DB", this::pingDatabase);

        /*
         * Sans base, l'état des migrations est indéterminé : on le déclare DOWN sans interroger
         * Flyway, dont le info() irait de toute façon buter sur la même connexion absente (et
         * consommerait une seconde fois la borne de temps).
         */
        String flyway = HealthResponse.UP.equals(db)
                ? probeWithTimeout("état Flyway", this::inspectMigrations)
                : HealthResponse.DOWN;

        String disk = checkDiskSpace();

        String status = HealthResponse.state(
                HealthResponse.UP.equals(db)
                        && HealthResponse.UP.equals(flyway)
                        && HealthResponse.UP.equals(disk));

        AppProperties.Info info = properties.getInfo();

        return new HealthResponse(
                status,
                db,
                flyway,
                disk,
                Instant.now().truncatedTo(ChronoUnit.SECONDS),
                info.getName(),
                info.getVersion()
        );
    }

    // ── Sondes base (bornées en temps) ───────────────────────────────────────

    /**
     * Exécute une sonde sur le thread dédié et l'attend au plus la borne configurée. La sonde rend
     * elle-même {@code UP}/{@code DOWN} ; toute exception ou dépassement de borne vaut {@code DOWN}.
     */
    private String probeWithTimeout(String label, Callable<String> probe) {
        int timeoutSeconds = properties.getHealth().getDbPingTimeoutSeconds();
        Future<String> future = pingExecutor.submit(probe);

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // cancel(true) retire la tâche de la file si elle n'a pas démarré, et interrompt sinon.
            future.cancel(true);
            log.warn("Health check: {} au-delà de {} s → DOWN", label, timeoutSeconds);
            return HealthResponse.DOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Health check: {} interrompu → DOWN", label);
            return HealthResponse.DOWN;
        } catch (Exception e) {
            // ExecutionException (SQL, pool épuisé, driver…) : toute panne se lit DOWN.
            log.warn("Health check: {} en échec → DOWN ({})", label, e.getMessage());
            return HealthResponse.DOWN;
        }
    }

    private String pingDatabase() {
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        return HealthResponse.UP;
    }

    /**
     * Répond à une question que le {@code SELECT 1} ne pose pas : « ce binaire tourne-t-il sur le
     * schéma qu'il attend ? ». Une migration en attente (déploiement avec Flyway désactivé, jar
     * plus récent que la base) ou en échec (migration interrompue en cours de route) laisse
     * l'application démarrée mais désalignée du schéma — donc {@code DOWN}.
     */
    private String inspectMigrations() {
        Flyway flyway = flywayProvider.getIfAvailable();
        if (flyway == null) {
            // Migrations désactivées : rien à vérifier, on ne fait pas tomber le statut pour autant.
            log.debug("Health check: pas de bean Flyway (migrations désactivées) → non évalué");
            return HealthResponse.UP;
        }

        MigrationInfoService info = flyway.info();

        MigrationInfo[] pending = info.pending();
        if (pending.length > 0) {
            log.warn("Health check: {} migration(s) Flyway en attente (première : {}) → DOWN",
                    pending.length, pending[0].getVersion());
            return HealthResponse.DOWN;
        }

        return Arrays.stream(info.all())
                .filter(migration -> migration.getState().isFailed())
                .findFirst()
                .map(failed -> {
                    log.warn("Health check: migration Flyway en échec ({}) → DOWN", failed.getVersion());
                    return HealthResponse.DOWN;
                })
                .orElse(HealthResponse.UP);
    }

    // ── Sonde disque (locale, non bornée) ────────────────────────────────────

    /**
     * Contrairement à la mémoire, l'espace disque est un vrai état binaire : stable entre deux
     * scrutations, non réparable tout seul, et fatal au parsing (upload jusqu'à 50 Mo + fichiers
     * temporaires PDFBox). Pas de borne de temps ici : l'appel est un syscall local.
     */
    private String checkDiskSpace() {
        File path = new File(properties.getHealth().getDiskPath());
        long minFreeMb = properties.getHealth().getMinFreeDiskMb();

        if (!path.exists()) {
            // Chemin mal configuré : getUsableSpace() rendrait 0, autant dire pourquoi.
            log.warn("Health check: chemin disque surveillé introuvable ({}) → DOWN", path.getAbsolutePath());
            return HealthResponse.DOWN;
        }

        long freeMb = path.getUsableSpace() / BYTES_PER_MB;
        if (freeMb < minFreeMb) {
            log.warn("Health check: espace libre {} Mo sur {} < plancher {} Mo → DOWN",
                    freeMb, path.getAbsolutePath(), minFreeMb);
            return HealthResponse.DOWN;
        }

        return HealthResponse.UP;
    }

    @PreDestroy
    void shutdown() {
        pingExecutor.shutdownNow();
    }
}
