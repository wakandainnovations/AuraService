package com.aura.service.enums;

/**
 * The regional film industries we classify movies under, each paired with the
 * language its films are made in. The {@code language} field of a movie entity
 * is derived from its {@code industry} via this mapping so the two stay
 * consistent. Industry matching is case-insensitive.
 */
public enum MovieIndustry {
    SANDALWOOD("Kannada"),
    BOLLYWOOD("Hindi"),
    TOLLYWOOD("Telugu"),
    KOLLYWOOD("Tamil"),
    MOLLYWOOD("Malayalam");

    private final String language;

    MovieIndustry(String language) {
        this.language = language;
    }

    public String getLanguage() {
        return language;
    }

    /**
     * Returns the language for the given industry name, or {@code null} if the
     * industry is blank or not one of the recognized regional industries (e.g.
     * "Hollywood"), in which case the caller should fall back to a supplied
     * language.
     */
    public static String languageFor(String industry) {
        if (industry == null || industry.isBlank()) {
            return null;
        }
        for (MovieIndustry value : values()) {
            if (value.name().equalsIgnoreCase(industry.trim())) {
                return value.language;
            }
        }
        return null;
    }

    /**
     * Reverse of {@link #languageFor(String)}: returns the display name of the regional industry
     * for a given language (e.g. "Kannada" → "Sandalwood"), or {@code null} if the language isn't
     * one of the five recognized regional languages.
     */
    public static String industryFor(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        for (MovieIndustry value : values()) {
            if (value.language.equalsIgnoreCase(language.trim())) {
                return capitalize(value.name());
            }
        }
        return null;
    }

    private static String capitalize(String enumName) {
        return enumName.charAt(0) + enumName.substring(1).toLowerCase();
    }
}
