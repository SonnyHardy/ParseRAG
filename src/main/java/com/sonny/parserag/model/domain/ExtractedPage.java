package com.sonny.parserag.model.domain;

public record ExtractedPage(
        int pageNumber,
        String rawText,
        boolean hasImages,
        boolean likelyHasTable
) {}
