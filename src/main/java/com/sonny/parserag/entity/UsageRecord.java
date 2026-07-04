package com.sonny.parserag.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Compteur de consommation d'une clé API sur un cycle mensuel.
 * <p>
 * Un enregistrement par (clé, cycle). Le cycle est « anniversaire » : ancré sur le jour de
 * création de la clé (cf. {@code UsageTrackingService}). {@code yearMonth} identifie le cycle
 * par son mois de <em>début</em> (format {@code "2025-05"}) — chaque mois calendaire ne contient
 * qu'une occurrence du jour d'ancrage, le mapping est donc unique.
 */
@Entity
@Table(name = "usage_records")
@Data
public class UsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "api_key_id", nullable = false, columnDefinition = "uuid")
    private UUID apiKeyId;

    @Column(name = "year_month", nullable = false, length = 7)
    private String yearMonth;

    @Column(name = "docs_processed")
    private int docsProcessed = 0;

    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;
}
