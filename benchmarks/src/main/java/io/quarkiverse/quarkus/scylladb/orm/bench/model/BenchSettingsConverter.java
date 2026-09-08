package io.quarkiverse.quarkus.scylladb.orm.bench.model;

import io.quarkiverse.quarkus.scylladb.orm.converter.AttributeConverter;

public class BenchSettingsConverter implements AttributeConverter<BenchSettings, String> {

    @Override
    public String toCqlColumn(BenchSettings attribute) {
        return attribute == null ? null : attribute.theme() + ":" + attribute.fontSize();
    }

    @Override
    public BenchSettings toEntityAttribute(String column) {
        if (column == null) {
            return null;
        }
        int sep = column.lastIndexOf(':');
        return new BenchSettings(column.substring(0, sep), Integer.parseInt(column.substring(sep + 1)));
    }
}
