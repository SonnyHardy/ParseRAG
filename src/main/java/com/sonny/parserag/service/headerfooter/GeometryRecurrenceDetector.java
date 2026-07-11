package com.sonny.parserag.service.headerfooter;

import com.sonny.parserag.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Couche PRIMAIRE — méthode page-association (Lin) : un bloc est confirmé header/footer
 * s'il est <strong>dans la bande de marge</strong> (header/footer) <strong>et</strong> que
 * son texte normalisé <strong>récurre sur plusieurs pages</strong>.
 * <p>
 * Indépendante des features dimensionnelles, donc insensible au cas qui piège DBSCAN :
 * un footer long, collé au corps (ex. la mention « available free of charge » de NIST),
 * est ici capté, car il récurre en zone basse.
 * <p>
 * Deux raffinements :
 * <ul>
 *   <li><strong>Headers alternés</strong> (pages paires/impaires) : sur les documents courts
 *       chaque variante ne récurre que sur ~la moitié des pages ; un seuil parité-conscient
 *       les rattrape.</li>
 *   <li><strong>Garde-fou TOC/index</strong> : une clé dont le texte ressemble à une entrée
 *       d'index (« mot, 12, 45 ») n'est pas strippée même si sa forme masquée récurre.</li>
 * </ul>
 */
@Slf4j
@Component
@Order(1)
class GeometryRecurrenceDetector implements HeaderFooterDetector {

    /** Entrée d'index : se termine par ≥ 2 nombres séparés par des virgules. */
    private static final Pattern INDEX_ENTRY =
            Pattern.compile(".*\\p{L}.*,\\s*\\d+(\\s*,\\s*\\d+)+\\s*$");

    @Override
    public String name() {
        return "geometry+recurrence";
    }

    @Override
    public Set<TextBlock> detect(DetectionContext ctx) {
        AppProperties.HeaderFooterCleaning cfg = ctx.cfg();
        double headerRatio = cfg.getHeaderZoneRatio();
        double footerRatio = cfg.getFooterZoneRatio();

        // 1. Candidats en zone, indexés par clé de récurrence.
        Map<String, Set<Integer>>   pagesByKey  = new HashMap<>();
        Map<String, List<TextBlock>> blocksByKey = new HashMap<>();
        Map<String, String>          sampleByKey = new HashMap<>();

        for (TextBlock b : ctx.blocks()) {
            if (!b.inEdgeZone(headerRatio, footerRatio)) continue;
            String key = TextNormalizer.recurrenceKey(b.text());
            if (key.isEmpty()) continue;
            pagesByKey.computeIfAbsent(key, _ -> new HashSet<>()).add(b.page());
            blocksByKey.computeIfAbsent(key, _ -> new ArrayList<>()).add(b);
            sampleByKey.putIfAbsent(key, b.text());
        }

        // 2. Confirmation par récurrence cross-page (+ garde-fous).
        int threshold = Math.min(cfg.getMinRecurrentPages(), ctx.pageCount());
        Set<TextBlock> confirmed = new HashSet<>();
        int keptKeys = 0, rejectedKeys = 0;

        for (Map.Entry<String, Set<Integer>> e : pagesByKey.entrySet()) {
            Set<Integer> pages = e.getValue();
            if (!isRecurrent(pages, threshold, ctx.pageCount())) {
                rejectedKeys++;
                continue;
            }
            if (INDEX_ENTRY.matcher(sampleByKey.get(e.getKey())).matches()) {
                rejectedKeys++;
                continue; // garde-fou TOC/index
            }
            confirmed.addAll(blocksByKey.get(e.getKey()));
            keptKeys++;
        }

        log.debug("HF[geometry+recurrence] — docId: {}, threshold: {} pages, keys kept: {}, rejected: {}, blocks confirmed: {}",
                ctx.documentId(), threshold, keptKeys, rejectedKeys, confirmed.size());
        return confirmed;
    }

    /**
     * Récurrent si présent sur ≥ {@code threshold} pages, OU — pour les headers alternés des
     * courts documents — présent sur ≥ 2 pages de même parité et au moins {@code ceil(pageCount/4)}.
     */
    private boolean isRecurrent(Set<Integer> pages, int threshold, int pageCount) {
        if (pages.size() >= threshold) return true;
        if (pages.size() >= 2 && allSameParity(pages)) {
            int altThreshold = Math.max(2, (int) Math.ceil(pageCount / 4.0));
            return pages.size() >= altThreshold;
        }
        return false;
    }

    private boolean allSameParity(Set<Integer> pages) {
        int parity = -1;
        for (int p : pages) {
            int q = p & 1;
            if (parity == -1) parity = q;
            else if (parity != q) return false;
        }
        return true;
    }
}
