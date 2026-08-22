package com.sonny.parserag.service.health;

import com.sonny.parserag.observability.ParseRagMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Maintient la base éveillée par une requête périodique triviale (issue #15).
 *
 * <p><strong>Pourquoi ce composant existe.</strong> L'offre gratuite de Supabase met un projet en
 * pause après <strong>7 jours d'inactivité</strong>. Or, depuis le passage par la place de marché
 * (issue #54), le chemin public ne fait <em>aucune</em> lecture en base : l'authentification se
 * réduit à comparer un secret. Une semaine de trafic RapidAPI parfaitement normal peut donc laisser
 * la base intouchée et la faire basculer en pause.
 *
 * <p><strong>Ce que la pause coûterait.</strong> Pas seulement un {@code db: DOWN} sur
 * {@code /api/v1/health} : au redéploiement suivant, <strong>Flyway ne pourrait pas se connecter et
 * l'application ne démarrerait pas</strong>. La panne se déclarerait donc au pire moment, celui où
 * l'on croit livrer.
 *
 * <p><strong>Pourquoi le HEALTHCHECK du conteneur ne suffisait pas.</strong> Il interroge
 * {@code /actuator/health}, qui ne touche pas la base — c'est même ce qui rend son exemption
 * d'authentification acceptable (issue #58). Aucune des sondes automatiques existantes ne réveillait
 * donc quoi que ce soit.
 *
 * <p><strong>Une requête, pas une transaction.</strong> {@code SELECT 1} suffit à compter comme
 * activité et ne verrouille rien. À l'intervalle par défaut (6 h), cela fait quatre requêtes par
 * jour : négligeable devant les quotas du plan gratuit, et sept fois plus fréquent que nécessaire —
 * la marge absorbe un redémarrage, une coupure réseau ou une nuit d'incident sans que le compteur
 * d'inactivité reparte de zéro.
 *
 * <p><strong>Un échec ne fait jamais tomber l'application.</strong> L'exception est capturée et
 * comptée : ce composant ne sert à rien si sa propre panne passe inaperçue, mais il servirait encore
 * moins s'il empêchait le service de tourner.
 *
 * <p>Désactivable par {@code parserag.database.keep-alive.enabled=false} — inutile sur une base
 * auto-hébergée, qui ne se met pas en pause.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "parserag.database.keep-alive.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class DatabaseKeepAlive {

    private final JdbcTemplate jdbcTemplate;
    private final ParseRagMetrics metrics;

    /**
     * Le premier ping n'a pas lieu au démarrage : la connexion qui vient de s'ouvrir et les
     * migrations Flyway ont déjà remis le compteur d'inactivité à zéro. On attend donc un intervalle
     * plein avant de commencer.
     */
    @Scheduled(
            initialDelayString = "${parserag.database.keep-alive.interval-minutes:360}",
            fixedDelayString = "${parserag.database.keep-alive.interval-minutes:360}",
            timeUnit = TimeUnit.MINUTES)
    public void ping() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            metrics.databaseKeepAlive(true);
            log.debug("Ping base : la base reste éveillée");
        } catch (RuntimeException e) {
            // Ne pas relancer : une exception ici tuerait la planification pour de bon
            // (fixedDelay ne replanifie pas après une tâche en échec), et le composant cesserait
            // silencieusement de protéger ce qu'il est censé protéger.
            metrics.databaseKeepAlive(false);
            log.warn("Ping base en échec : sans lui, une base gratuite finira en pause", e);
        }
    }
}
