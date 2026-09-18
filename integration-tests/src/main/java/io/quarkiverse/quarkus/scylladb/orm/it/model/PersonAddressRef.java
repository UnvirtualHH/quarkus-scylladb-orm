package io.quarkiverse.quarkus.scylladb.orm.it.model;

import java.util.UUID;

import io.quarkiverse.quarkus.scylladb.orm.mapping.Column;

/** A projection whose component name differs from its snake_case column. */
public record PersonAddressRef(String name, @Column("address_id") UUID addressId) {
}
