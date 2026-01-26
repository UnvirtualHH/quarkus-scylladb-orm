package io.quarkiverse.quarkus.scylladb.orm.repository.util;

import java.util.regex.Pattern;

/**
 * Represents a sorting directive for CQL queries.
 * Column names are validated to prevent CQL injection attacks.
 */
public record Sortable(String column, boolean ascending) {

    // Pattern for valid CQL column names: alphanumeric and underscore, must start with letter or underscore
    private static final Pattern SAFE_COLUMN_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /**
     * Creates a Sortable with validation.
     */
    public Sortable {
        if (column != null && !column.isEmpty()) {
            validateColumnName(column);
        }
    }

    public static Sortable asc(String column) {
        return new Sortable(column, true);
    }

    public static Sortable desc(String column) {
        return new Sortable(column, false);
    }

    public String toCql() {
        if (column == null || column.isEmpty()) {
            return "";
        }
        return "ORDER BY " + column + (ascending ? " ASC" : " DESC");
    }

    private static void validateColumnName(String column) {
        if (!SAFE_COLUMN_PATTERN.matcher(column).matches()) {
            throw new IllegalArgumentException(
                    "Invalid column name: '" + column + "'. " +
                            "Column names must contain only alphanumeric characters and underscores, " +
                            "and must start with a letter or underscore.");
        }
    }
}
