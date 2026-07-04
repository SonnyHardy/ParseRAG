package com.sonny.parserag.service.usage;

import com.sonny.parserag.config.AppProperties;
import com.sonny.parserag.entity.ApiKey;
import com.sonny.parserag.model.response.UsageResponse;
import com.sonny.parserag.repository.UsageRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * Suivi de consommation mensuelle par clé API (issue #13).
 * <p>
 * Le cycle de facturation est « anniversaire » : ancré sur le <em>jour de création</em> de la
 * clé. Une clé créée le 2 mai se réinitialise le 2 de chaque mois. La période de stockage
 * ({@code year_month}) identifie un cycle par son mois de <em>début</em> — unique car chaque mois
 * calendaire ne contient qu'une occurrence du jour d'ancrage.
 * <p>
 * Les jours d'ancrage &gt; à la longueur du mois cible sont bornés (ancre 31 → 30 avril, 28/29
 * février), suivant la convention usuelle des cycles mensuels de fin de mois.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageTrackingService {

    private final UsageRecordRepository usageRecordRepository;
    private final AppProperties appProperties;

    /** Incrémente le compteur du cycle courant après un parse réussi. */
    @Transactional
    public void recordSuccessfulParse(ApiKey apiKey) {
        String period = periodKey(apiKey, LocalDate.now());
        usageRecordRepository.incrementUsage(apiKey.getId(), period);
        log.debug("Usage +1 — key: {}, cycle: {}", apiKey.getId(), period);
    }

    /** Nombre de documents consommés sur le cycle courant (0 si aucun parse encore). */
    public int currentCount(ApiKey apiKey) {
        String period = periodKey(apiKey, LocalDate.now());
        return usageRecordRepository.findByApiKeyIdAndYearMonth(apiKey.getId(), period)
                .map(r -> r.getDocsProcessed())
                .orElse(0);
    }

    /** Consommation du cycle courant pour l'endpoint GET /usage. */
    public UsageResponse currentUsage(ApiKey apiKey) {
        return buildUsage(apiKey, currentCount(apiKey), LocalDate.now());
    }

    /**
     * Assemble la réponse /usage à partir d'un compteur et d'une date donnés.
     * Logique pure (aucun accès DB) — {@code today} explicite pour rester déterministe en test.
     */
    UsageResponse buildUsage(ApiKey apiKey, int used, LocalDate today) {
        int limit = appProperties.getQuota().forPlan(apiKey.getPlan());
        return new UsageResponse(
                apiKey.getPlan().name().toLowerCase(),
                used,
                limit,
                Math.max(0, limit - used),
                resetDate(apiKey, today).toString(),
                periodKey(apiKey, today)
        );
    }

    // ── Logique de cycle « anniversaire » ────────────────────────────────────

    /** Jour d'ancrage = jour de création de la clé. */
    static int anchorDay(ApiKey apiKey) {
        LocalDateTime createdAt = apiKey.getCreatedAt();
        // Défensif : clé sans createdAt (jeu de données incomplet) → ancre au 1er.
        return createdAt != null ? createdAt.getDayOfMonth() : 1;
    }

    /** Date de début du cycle courant : dernière occurrence du jour d'ancrage ≤ aujourd'hui. */
    static LocalDate cycleStart(ApiKey apiKey, LocalDate today) {
        int anchor = anchorDay(apiKey);
        LocalDate thisMonthAnchor = anchoredDate(today.getYear(), today.getMonthValue(), anchor);
        if (!today.isBefore(thisMonthAnchor)) {
            return thisMonthAnchor;                 // cycle démarré ce mois-ci
        }
        LocalDate prev = today.minusMonths(1);
        return anchoredDate(prev.getYear(), prev.getMonthValue(), anchor); // démarré le mois dernier
    }

    /** Prochaine date de reset : occurrence du jour d'ancrage suivant le début du cycle courant. */
    static LocalDate resetDate(ApiKey apiKey, LocalDate today) {
        LocalDate nextMonth = cycleStart(apiKey, today).plusMonths(1);
        return anchoredDate(nextMonth.getYear(), nextMonth.getMonthValue(), anchorDay(apiKey));
    }

    /** Clé de stockage du cycle courant = year-month de son début (ex. "2025-05"). */
    static String periodKey(ApiKey apiKey, LocalDate today) {
        return YearMonth.from(cycleStart(apiKey, today)).toString();
    }

    /** Construit une date en bornant le jour à la longueur du mois cible. */
    static LocalDate anchoredDate(int year, int month, int anchorDay) {
        YearMonth ym = YearMonth.of(year, month);
        return ym.atDay(Math.min(anchorDay, ym.lengthOfMonth()));
    }
}
