package com.mel.aidev.project;

import java.util.Locale;

/**
 * Builds the LIKE patterns of the list screens.
 *
 * <p>A pattern is always produced, {@code %} when nothing is filtered, because the alternative — a
 * nullable parameter inside {@code concat} in the query — reaches PostgreSQL with no inferable type
 * and is planned as {@code bytea}. H2 accepts it, so the failure only appears in production.
 */
public final class SearchPattern {

    private SearchPattern() {}

    /** Case-insensitive contains pattern; the queries compare it against {@code lower(column)}. */
    public static String contains(String query) {
        return query == null || query.isBlank() ? "%" : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
    }
}
