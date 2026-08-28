package com.sonny.parserag.model.domain;

/**
 * Type de contenu extrait d'un document.
 * Utilisé par ConfidenceCalculatorService (Sprint 2)
 * pour appliquer la bonne formule de scoring par bloc.
 */
public enum ChunkType {

    /** Paragraphe issu du texte natif PDF. */
    PARAGRAPH,

    /** Tableau extrait par Tabula-java (Sprint 3). */
    TABLE,

    /** Légende ou titre d'une figure. */
    FIGURE_CAPTION,

    /** Ligne répétitive détectée comme header ou footer. */
    HEADER_ARTIFACT
}