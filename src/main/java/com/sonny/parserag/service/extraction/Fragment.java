package com.sonny.parserag.service.extraction;

/**
 * Run de texte capturé lors d'une passe PDFBox : contenu + plage X + Y baseline.
 * <p>
 * Primitive géométrique partagée par {@link PdfTextExtractorService} (réassemblage
 * du texte) et {@link TableRegionDetector} (délimitation des régions de tableau).
 * Package-privé : strictement interne au package {@code service.extraction}.
 */
record Fragment(String text, float xStart, float xEnd, float y) {}
