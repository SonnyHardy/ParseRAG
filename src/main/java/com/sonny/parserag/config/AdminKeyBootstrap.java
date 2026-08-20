package com.sonny.parserag.config;

import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.entity.Plan;
import com.sonny.parserag.repository.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Crée la clé d'administration au démarrage, à partir de son <strong>hash</strong> fourni par
 * l'environnement (issue #56).
 *
 * <p><strong>Pourquoi pas une migration.</strong> V1 semait une clé dont le secret était « 123 » :
 * toute base neuve contenait un identifiant valide, publié dans le dépôt. Un secret n'a rien à
 * faire dans un fichier versionné — et une migration ne s'exécutant qu'une fois, elle ne saurait de
 * toute façon pas suivre une rotation de clé.
 *
 * <p><strong>Ce que la classe ne fait jamais.</strong> Elle ne génère pas de clé et ne connaît pas
 * le secret : seul son SHA-256 lui est confié, exactement comme ce que {@code ApiKeyFilter}
 * compare. L'opérateur garde le secret, la base n'en voit que l'empreinte.
 *
 * <p><strong>Sans {@code ADMIN_KEY_HASH}, rien n'est créé</strong> — et c'est volontaire : un
 * défaut de configuration doit laisser l'API sans clé d'administration plutôt que d'en inventer
 * une. L'avertissement au démarrage dit alors ce qui est perdu ({@code GET /api/v1/health}
 * inaccessible), sans quoi le silence passerait pour un succès.
 *
 * <p>Idempotent : rejoué à chaque démarrage, il ne crée la clé qu'une fois, et promeut une clé
 * existante dont le drapeau {@code admin} serait tombé. Deux instances démarrant ensemble ne se
 * marchent pas dessus — la contrainte d'unicité tranche, et la violation est traitée comme un
 * succès de l'autre instance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminKeyBootstrap implements ApplicationRunner {

    private final AppProperties appProperties;
    private final ApiKeyRepository apiKeyRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppProperties.Security config = appProperties.getSecurity();

        if (!config.hasAdminKeyHash()) {
            log.warn("ADMIN_KEY_HASH absent : aucune clé d'administration ne sera créée. "
                    + "GET /api/v1/health restera inaccessible (404 pour tous).");
            return;
        }

        String hash = config.getAdminKeyHash().strip();
        Optional<ApiKey> existing = apiKeyRepository.findByKeyHash(hash);

        if (existing.isPresent()) {
            promoteIfNeeded(existing.get());
            return;
        }

        try {
            apiKeyRepository.save(newAdminKey(hash, config.getAdminKeyEmail()));
            log.info("Clé d'administration créée depuis ADMIN_KEY_HASH.");
        } catch (DataIntegrityViolationException e) {
            // Démarrage concurrent : une autre instance vient de l'insérer. La contrainte
            // d'unicité a fait son travail, il n'y a rien à réparer.
            log.info("Clé d'administration déjà créée par une autre instance.");
        }
    }

    /**
     * Une clé existante mais dégradée (drapeau retiré, clé désactivée) est remise en état : c'est
     * le cas d'une base restaurée ou d'une manipulation manuelle, et laisser l'API sans
     * administrateur au motif que la ligne existe serait le pire des deux mondes.
     */
    private void promoteIfNeeded(ApiKey key) {
        boolean changed = false;

        if (!key.isAdmin()) {
            key.setAdmin(true);
            changed = true;
        }
        if (!key.isActive()) {
            key.setActive(true);
            changed = true;
        }

        if (changed) {
            apiKeyRepository.save(key);
            log.warn("Clé d'administration existante remise en état (admin/active).");
        }
    }

    /**
     * Plan {@link Plan#FREE} : la clé d'administration sert au diagnostic, pas au débit. Lui
     * donner le plan le plus large ferait d'une fuite de cette clé un accès de production complet ;
     * un opérateur qui a besoin de volume se crée une clé dédiée.
     */
    private ApiKey newAdminKey(String hash, String email) {
        ApiKey key = new ApiKey();
        key.setKeyHash(hash);
        key.setOwnerEmail(email);
        key.setPlan(Plan.FREE);
        key.setActive(true);
        key.setAdmin(true);
        key.setCreatedAt(LocalDateTime.now());
        return key;
    }
}
