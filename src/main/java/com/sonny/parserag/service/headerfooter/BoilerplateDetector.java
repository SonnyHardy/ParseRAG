package com.sonny.parserag.service.headerfooter;

import com.sonny.parserag.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Couche RÈGLES DE CONTENU — capte le boilerplate que la récurrence cross-page ne peut pas voir
 * (présent sur une seule page) et, surtout, les textes de <strong>marge pivotés à 90°</strong>
 * (tampon arXiv, mention NIST « available free of charge » imprimée verticalement) : PDFBox les
 * restitue en texte contigu, mais leurs coordonnées sont en repère pivoté, donc le test de zone
 * header/footer est trompé. On distingue donc :
 * <ul>
 *   <li>{@link #STRONG_PATTERNS} : motifs non ambigus, strippés <strong>où qu'ils soient</strong>
 *       (insensibles au repère pivoté) ;</li>
 *   <li>{@link #ZONE_PATTERNS} : pagination, strippée <strong>uniquement en bande de marge</strong>
 *       (un nombre seul au milieu du corps n'est pas du bruit).</li>
 * </ul>
 */
@Slf4j
@Component
@Order(2)
class BoilerplateDetector implements HeaderFooterDetector {

    /** Boilerplate non ambigu — supprimé quelle que soit la position (gère le texte de marge pivoté). */
    private static final List<Pattern> STRONG_PATTERNS = List.of(
            Pattern.compile("^arxiv:\\s*\\d{4}\\.\\d{4,5}(v\\d+)?\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("available free of charge", Pattern.CASE_INSENSITIVE),
            Pattern.compile("permission to make digital or hard copies", Pattern.CASE_INSENSITIVE),
            Pattern.compile("copyright held by", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bACM ISBN\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^©\\s*\\d{4}\\b"),
            Pattern.compile("^https?://\\S+$")  // ligne réduite à une URL nue (footer DOI, etc.)
    );

    /** Pagination — supprimée seulement en bande de marge. */
    private static final List<Pattern> ZONE_PATTERNS = List.of(
            Pattern.compile("^\\d{1,4}$"),
            Pattern.compile("^[ivxlcdm]{1,6}$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^page\\s+\\d+(\\s*(/|of|sur|de|von|di|z)\\s*\\d+)?$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^-\\s*\\d+\\s*-$"),
            Pattern.compile("^\\p{L}+\\s+\\d+\\s+\\p{L}+\\s+\\d+$")  // « Seite 1 von 2 », « Página 1 de 2 »
    );

    @Override
    public String name() {
        return "boilerplate";
    }

    @Override
    public Set<TextBlock> detect(DetectionContext ctx) {
        AppProperties.HeaderFooterCleaning cfg = ctx.cfg();
        double headerRatio = cfg.getHeaderZoneRatio();
        double footerRatio = cfg.getFooterZoneRatio();

        Set<TextBlock> confirmed = new HashSet<>();
        int strong = 0, zoned = 0;
        for (TextBlock b : ctx.blocks()) {
            if (ctx.alreadyConfirmed().contains(b)) continue;
            String normalized = TextNormalizer.whitespace(b.text());
            if (normalized.isEmpty()) continue;

            if (matchesAny(STRONG_PATTERNS, normalized)) {
                confirmed.add(b);
                strong++;
            } else if (b.inEdgeZone(headerRatio, footerRatio) && matchesAny(ZONE_PATTERNS, normalized)) {
                confirmed.add(b);
                zoned++;
            }
        }

        log.info("HF[boilerplate] — docId: {}, confirmed: {} (strong: {}, zoned: {})",
                ctx.documentId(), confirmed.size(), strong, zoned);
        return confirmed;
    }

    private boolean matchesAny(List<Pattern> patterns, String text) {
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) return true;
        }
        return false;
    }
}
