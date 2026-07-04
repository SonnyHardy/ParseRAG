package com.sonny.parserag.repository;

import com.sonny.parserag.entity.UsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {

    /** Lecture du compteur d'un cycle donné (filtre quota + endpoint /usage). */
    Optional<UsageRecord> findByApiKeyIdAndYearMonth(UUID apiKeyId, String yearMonth);

    /**
     * Incrément atomique du compteur du cycle courant (upsert Postgres).
     * Crée la ligne à 1 si le cycle n'existe pas encore, sinon +1 — insensible aux races
     * concurrentes sur la même ligne grâce à la contrainte UNIQUE(api_key_id, year_month).
     */
    @Modifying
    @Query(value = """
            INSERT INTO usage_records (api_key_id, year_month, docs_processed, last_updated)
            VALUES (:apiKeyId, :yearMonth, 1, NOW())
            ON CONFLICT (api_key_id, year_month)
            DO UPDATE SET docs_processed = usage_records.docs_processed + 1,
                          last_updated = NOW()
            """, nativeQuery = true)
    void incrementUsage(@Param("apiKeyId") UUID apiKeyId, @Param("yearMonth") String yearMonth);
}
