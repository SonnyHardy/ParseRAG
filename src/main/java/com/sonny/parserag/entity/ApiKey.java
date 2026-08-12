package com.sonny.parserag.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "api_keys")
@Data
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    @Column(name = "owner_email", nullable = false, unique = true)
    private String ownerEmail;

    @Enumerated(EnumType.STRING)
    private Plan plan = Plan.FREE;

    private boolean active = true;

    /**
     * Clé administrateur (développeur). Seule une clé {@code admin} peut appeler les endpoints
     * réservés — aujourd'hui {@code GET /api/v1/health} (issue #37).
     */
    @Column(nullable = false)
    private boolean admin = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
