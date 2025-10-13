package io.quarkiverse.quarkus.scylladb.orm.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.quarkiverse.quarkus.scylladb.orm.converter.AttributeConverter;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Convert {
    Class<? extends AttributeConverter<?, ?>> value();
}
