package com.sonny.parserag.service.headerfooter;

import java.util.regex.Pattern;

/** Normalisations partagées par les couches de nettoyage header/footer. */
final class TextNormalizer {

    private static final Pattern WHITESPACE        = Pattern.compile("\\s+");
    private static final Pattern DIGITS            = Pattern.compile("\\d+");
    private static final String  DIGIT_PLACEHOLDER = "#";

    private TextNormalizer() {}

    /** Espaces réduits à un seul, trim. Sert au strip exact (les chiffres sont conservés). */
    static String whitespace(String s) {
        if (s == null || s.isEmpty()) return "";
        return WHITESPACE.matcher(s.strip()).replaceAll(" ");
    }

    /**
     * Espaces normalisés puis séquences de chiffres masquées en {@code #}.
     * Sert aux clés de récurrence cross-page : « Seite 1 von 2 » et « Seite 2 von 2 »
     * partagent « Seite # von # ».
     */
    static String recurrenceKey(String s) {
        if (s == null || s.isEmpty()) return "";
        return DIGITS.matcher(whitespace(s)).replaceAll(DIGIT_PLACEHOLDER);
    }
}
