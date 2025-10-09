package io.quarkiverse.quarkus.scylladb.orm.repository.util;

public record Sortable(String column, boolean ascending) {
    public static Sortable asc(String column) {
        return new Sortable(column, true);
    }

    public static Sortable desc(String column) {
        return new Sortable(column, false);
    }

    public String toCql() {
        if (column == null)
            return "";
        return "ORDER BY " + column + (ascending ? " ASC" : " DESC");
    }
}
