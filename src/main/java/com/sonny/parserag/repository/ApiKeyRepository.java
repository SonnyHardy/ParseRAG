package com.sonny.parserag.repository;

import com.sonny.parserag.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyHashAndActiveTrue(String keyHash);

    /**
     * Sans le filtre {@code active} : l'amorçage de la clé d'administration (issue #56) doit voir
     * une clé désactivée pour la remettre en état, là où l'authentification ne doit jamais la voir.
     */
    Optional<ApiKey> findByKeyHash(String keyHash);
}
