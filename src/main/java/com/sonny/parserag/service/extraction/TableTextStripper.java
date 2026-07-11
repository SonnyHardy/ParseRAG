package com.sonny.parserag.service.extraction;

import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.model.domain.ExtractedPage;
import com.sonny.parserag.model.domain.TableRegion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Retire du {@code rawText} de chaque page les lignes couvertes par une région de tableau
 * (issue #9), pour éviter qu'un tableau soit présent <em>deux fois</em> dans la sortie : une fois
 * en chunk texte « bouillie » et une fois en chunk {@code TABLE} structuré.
 *
 * <p>Approche géométrique (analogue à {@code LineStripper} pour les header/footer) : on reparse les
 * fragments de la page, on garde ceux dont le centre tombe dans une boîte de région, on les
 * réassemble en lignes, puis on retire du {@code rawText} les lignes correspondantes (comparaison
 * <strong>insensible aux espaces</strong>). En cas d'écart, la ligne n'est pas retirée — pas de
 * sur-suppression. Produit un nouveau {@link ExtractedDocument} (immutabilité préservée).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableTextStripper {

    private static final float LINE_Y_TOLERANCE_PT = 2f;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final PageGeometryAnalyzer geometry;

    public ExtractedDocument strip(byte[] pdfBytes, ExtractedDocument doc, List<TableRegion> regions) {
        if (doc == null || regions == null || regions.isEmpty()) return doc;

        Map<Integer, List<TableRegion>> byPage = new HashMap<>();
        for (TableRegion r : regions) byPage.computeIfAbsent(r.page(), _ -> new ArrayList<>()).add(r);

        Map<Integer, Set<String>> linesByPage = new HashMap<>();
        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            for (Map.Entry<Integer, List<TableRegion>> e : byPage.entrySet()) {
                int page = e.getKey();
                if (page < 1 || page > pdf.getNumberOfPages()) continue;
                List<Fragment> inside = fragmentsInRegions(geometry.fragments(pdf, page), e.getValue());
                Set<String> lines = assembleLines(inside);
                if (!lines.isEmpty()) linesByPage.put(page, lines);
            }
        } catch (IOException ex) {
            log.warn("Table text stripping skipped — cannot load PDF (docId {})", doc.documentId(), ex);
            return doc;
        }

        int removed = 0;
        List<ExtractedPage> cleaned = new ArrayList<>(doc.pages().size());
        for (ExtractedPage page : doc.pages()) {
            Set<String> toRemove = linesByPage.getOrDefault(page.pageNumber(), Set.of());
            StripResult res = stripLines(page.rawText(), toRemove);
            removed += res.removed();
            cleaned.add(new ExtractedPage(page.pageNumber(), res.text(),
                    page.hasImages(), page.likelyHasTable(), page.reorderSuspectLines()));
        }

        log.debug("Table text stripping — docId: {}, lines removed: {}", doc.documentId(), removed);
        return new ExtractedDocument(doc.documentId(), doc.pageCount(),
                doc.detectedLanguage(), doc.title(), cleaned);
    }

    /** Fragments dont le centre tombe dans l'une des régions de la page. */
    private List<Fragment> fragmentsInRegions(List<Fragment> frags, List<TableRegion> regions) {
        List<Fragment> inside = new ArrayList<>();
        for (Fragment f : frags) {
            float cx = (f.xStart() + f.xEnd()) / 2f;
            float cy = f.y();
            for (TableRegion r : regions) {
                if (cx >= r.x0() && cx <= r.x1() && cy >= r.y0() && cy <= r.y1()) {
                    inside.add(f);
                    break;
                }
            }
        }
        return inside;
    }

    /** Regroupe les fragments par baseline et produit l'ensemble des lignes normalisées (espaces). */
    private Set<String> assembleLines(List<Fragment> frags) {
        Set<String> lines = new HashSet<>();
        if (frags.isEmpty()) return lines;

        List<Fragment> sorted = new ArrayList<>(frags);
        sorted.sort(Comparator.comparingDouble(Fragment::y).thenComparingDouble(Fragment::xStart));

        StringBuilder line = new StringBuilder();
        float curY = sorted.getFirst().y();
        for (Fragment f : sorted) {
            if (Math.abs(f.y() - curY) > LINE_Y_TOLERANCE_PT) {
                addLine(lines, line.toString());
                line.setLength(0);
                curY = f.y();
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(f.text());
        }
        addLine(lines, line.toString());
        return lines;
    }

    private void addLine(Set<String> lines, String raw) {
        String norm = normalize(raw);
        if (!norm.isEmpty()) lines.add(norm);
    }

    private StripResult stripLines(String rawText, Set<String> linesToRemove) {
        if (rawText == null || rawText.isEmpty() || linesToRemove.isEmpty()) {
            return new StripResult(rawText == null ? "" : rawText, 0);
        }
        String[] lines = rawText.split("\n", -1);
        StringBuilder out = new StringBuilder(rawText.length());
        int removed = 0;
        boolean first = true;
        for (String l : lines) {
            if (linesToRemove.contains(normalize(l))) {
                removed++;
                continue;
            }
            if (!first) out.append('\n');
            out.append(l);
            first = false;
        }
        return new StripResult(out.toString().strip(), removed);
    }

    private String normalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return WHITESPACE.matcher(s.strip()).replaceAll(" ");
    }

    private record StripResult(String text, int removed) {}
}
