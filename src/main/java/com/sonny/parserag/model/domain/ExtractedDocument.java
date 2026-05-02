package com.sonny.parserag.model.domain;

import java.util.List;

public record ExtractedDocument(
        String documentId,
        int pageCount,
        String detectedLanguage,
        String title,
        List<ExtractedPage> pages
) {}
