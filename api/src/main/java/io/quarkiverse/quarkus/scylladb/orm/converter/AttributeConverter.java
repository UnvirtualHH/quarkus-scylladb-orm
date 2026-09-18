package io.quarkiverse.quarkus.scylladb.orm.converter;

public interface AttributeConverter<EntityType, CqlType> {
    CqlType toCqlColumn(EntityType value);

    EntityType toEntityAttribute(CqlType value);
}