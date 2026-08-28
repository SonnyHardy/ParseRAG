package com.sonny.parserag.service.fallback;

/**
 * Consignes envoyées au modèle de vision, <strong>identiques d'un fournisseur à l'autre</strong>
 * (issue #28) : elles décrivent la tâche d'extraction, pas l'API qui la sert. Les garder ici évite
 * qu'un ajustement de prompt ne s'applique qu'à un seul provider — et donc que la comparaison de
 * qualité Gemini/OpenAI porte sur autre chose que le modèle.
 *
 * <p>La forme JSON demandée est aussi exprimée en {@code responseSchema} côté Gemini ; le prompt la
 * répète car il porte en plus les <em>règles</em> d'extraction (pas de fusion de cellules, exclusion
 * des en-têtes courants, ordre de lecture) qu'un schéma ne sait pas exprimer.
 */
final class VisionPrompts {

    private VisionPrompts() {
    }

    // ── Prompts « région de tableau » (issue #9) ──────────────────────────────────────────
    static final String TABLE_SYSTEM = """
            You are a precise table extraction engine. You receive an image cropped to a single \
            table and return ONLY its content as strict JSON, no commentary.""";

    static final String TABLE_USER = """
            Extract the table in this image as JSON with exactly this shape:
            {"headers": ["col1", "col2", ...], "rows": [["c1", "c2", ...], ...]}
            Rules: one array per row, cells as plain strings (empty string if a cell is blank).
            Every row MUST have exactly the same number of cells as "headers" — pad missing cells \
            with an empty string and never drop or merge cells. Keep dash/hyphen cells ("-") as \
            their own cell. Do not collapse a multi-level header into a single column.
            Keep the original reading order, do not invent columns. If the image contains no table, \
            return {"headers": [], "rows": []}.""";

    // ── Prompts « page scannée plein-page » (issue #11) ───────────────────────────────────
    static final String PAGE_SYSTEM = """
            You are a precise document extraction engine. You receive an image of a single scanned \
            document page and return ONLY its content as strict JSON, no commentary.""";

    static final String PAGE_USER = """
            Extract all text and tables from this document page as JSON with exactly this shape:
            {"text": "...", "tables": [{"headers": ["col1", ...], "rows": [["c1", ...], ...]}, ...]}
            For "text": clean reading-order paragraphs of the body text, excluding running \
            headers/footers and excluding any text that belongs to a table.
            For each table: one array per row, cells as plain strings (empty string if blank); \
            every row MUST have the same number of cells as its "headers".
            If the page has no tables, return "tables": []. If it has no body text, return "text": "".
            Respond ONLY with the JSON object.""";
}
