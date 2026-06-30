package com.sonny.parserag.service.headerfooter;

import com.sonny.parserag.model.domain.ExtractedDocument;
import com.sonny.parserag.model.domain.ExtractedPage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Retire du {@code rawText} de chaque page les lignes correspondant aux blocs confirmés
 * header/footer. La comparaison est insensible aux espaces (les écarts reconstitués
 * diffèrent entre l'extracteur de texte et l'extracteur de blocs).
 */
@Component
class LineStripper {

    record Result(List<ExtractedPage> pages, int linesRemoved) {}

    Result strip(ExtractedDocument doc, Set<TextBlock> confirmed) {
        Map<Integer, Set<String>> linesByPage = new HashMap<>();
        for (TextBlock b : confirmed) {
            Set<String> lines = linesByPage.computeIfAbsent(b.page(), k -> new HashSet<>());
            for (String line : b.text().split("\n", -1)) {
                String normalized = TextNormalizer.whitespace(line);
                if (!normalized.isEmpty()) lines.add(normalized);
            }
        }

        int removed = 0;
        List<ExtractedPage> cleaned = new ArrayList<>(doc.pages().size());
        for (ExtractedPage page : doc.pages()) {
            Set<String> toRemove = linesByPage.getOrDefault(page.pageNumber(), Set.of());
            StripResult res = stripLines(page.rawText(), toRemove);
            removed += res.removed();
            cleaned.add(new ExtractedPage(page.pageNumber(), res.text(),
                    page.hasImages(), page.likelyHasTable(), page.readingOrderScore()));
        }
        return new Result(cleaned, removed);
    }

    private StripResult stripLines(String rawText, Set<String> linesToRemove) {
        if (rawText == null || rawText.isEmpty() || linesToRemove.isEmpty()) {
            return new StripResult(rawText == null ? "" : rawText, 0);
        }
        String[] lines = rawText.split("\n", -1);
        StringBuilder out = new StringBuilder(rawText.length());
        int removed = 0;
        boolean first = true;
        for (String line : lines) {
            if (linesToRemove.contains(TextNormalizer.whitespace(line))) {
                removed++;
                continue;
            }
            if (!first) out.append('\n');
            out.append(line);
            first = false;
        }
        return new StripResult(out.toString().strip(), removed);
    }

    private record StripResult(String text, int removed) {}
}
