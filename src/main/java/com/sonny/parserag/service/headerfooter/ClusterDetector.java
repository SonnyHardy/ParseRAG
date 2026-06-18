package com.sonny.parserag.service.headerfooter;

import com.sonny.parserag.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math4.legacy.ml.clustering.Cluster;
import org.apache.commons.math4.legacy.ml.clustering.DBSCANClusterer;
import org.apache.commons.math4.legacy.ml.clustering.DoublePoint;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Couche SECONDAIRE — DBSCAN, en filet de sécurité après les couches primaires.
 * <p>
 * Sur les blocs <strong>en zone de marge et encore non confirmés</strong>, on clusterise les
 * features page-normalisées. Le plus gros cluster est considéré comme du « corps résiduel »
 * et ignoré ; tout autre cluster <strong>couvrant ≥ 2 pages distinctes</strong> est confirmé.
 * <p>
 * Volontairement conservateur : il ne touche ni au corps (hors zone), ni aux blocs isolés
 * (bruit / one-off), afin de ne pas réintroduire la sur-suppression. Il rattrape les
 * header/footer à texte légèrement variable d'une page à l'autre (donc ratés par la
 * récurrence exacte) mais géométriquement stables.
 */
@Slf4j
@Component
@Order(3)
class ClusterDetector implements HeaderFooterDetector {

    /** log(1+~1100) ≈ 7 → borne la feature longueur dans [0, 1]. */
    private static final double LEN_LOG_SCALE = 7.0;

    @Override
    public String name() {
        return "cluster";
    }

    @Override
    public Set<TextBlock> detect(DetectionContext ctx) {
        AppProperties.HeaderFooterCleaning cfg = ctx.cfg();
        double headerRatio = cfg.getHeaderZoneRatio();
        double footerRatio = cfg.getFooterZoneRatio();

        // Blocs en zone, non déjà confirmés.
        List<TextBlock> candidates = new ArrayList<>();
        for (TextBlock b : ctx.blocks()) {
            if (ctx.alreadyConfirmed().contains(b)) continue;
            if (b.inEdgeZone(headerRatio, footerRatio)) candidates.add(b);
        }
        if (candidates.size() < cfg.getDbscanMinSamples()) {
            log.info("HF[cluster] — docId: {}, skipped ({} candidates < {})",
                    ctx.documentId(), candidates.size(), cfg.getDbscanMinSamples());
            return Set.of();
        }

        List<DoublePoint> points = new ArrayList<>(candidates.size());
        Map<DoublePoint, TextBlock> blockByPoint = new IdentityHashMap<>();
        for (TextBlock b : candidates) {
            DoublePoint p = new DoublePoint(featuresOf(b));
            points.add(p);
            blockByPoint.put(p, b);
        }

        DBSCANClusterer<DoublePoint> dbscan =
                new DBSCANClusterer<>(cfg.getDbscanEps(), cfg.getDbscanMinSamples());
        List<Cluster<DoublePoint>> clusters = dbscan.cluster(points);
        if (clusters.isEmpty()) {
            log.info("HF[cluster] — docId: {}, no clusters formed", ctx.documentId());
            return Set.of();
        }

        // Le plus gros cluster = corps résiduel → ignoré.
        Cluster<DoublePoint> body = clusters.stream()
                .max(Comparator.comparingInt(c -> c.getPoints().size()))
                .orElseThrow();

        Set<TextBlock> confirmed = new HashSet<>();
        for (Cluster<DoublePoint> cluster : clusters) {
            if (cluster == body) continue;
            List<TextBlock> members = new ArrayList<>();
            Set<Integer> pages = new HashSet<>();
            for (DoublePoint p : cluster.getPoints()) {
                TextBlock b = blockByPoint.get(p);
                members.add(b);
                pages.add(b.page());
            }
            if (pages.size() >= 2) confirmed.addAll(members); // récurrent géométriquement
        }

        log.info("HF[cluster] — docId: {}, clusters: {}, body size: {}, blocks confirmed: {}",
                ctx.documentId(), clusters.size(), body.getPoints().size(), confirmed.size());
        return confirmed;
    }

    /** Features page-normalisées, déjà bornées dans [0, 1]. */
    private double[] featuresOf(TextBlock b) {
        double xRel      = b.x0() / b.pageWidth();
        double yRel      = b.y0() / b.pageHeight();
        double widthRel  = (b.x1() - b.x0()) / b.pageWidth();
        double heightRel = (b.y1() - b.y0()) / b.pageHeight();
        double lenScaled = Math.min(1.0, Math.log1p(b.text().length()) / LEN_LOG_SCALE);
        return new double[]{xRel, yRel, widthRel, heightRel, lenScaled};
    }
}
