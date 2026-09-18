package io.quarkiverse.quarkus.scylladb.orm.it.model;

import java.util.UUID;

import io.quarkiverse.quarkus.scylladb.orm.mapping.Column;

/**
 * The class (non-record) counterpart of {@link PersonAddressRef}. DTO projections failed
 * to generate at all until the mapping lambda stopped nesting JavaPoet statements, and no
 * test used one — so this one exists to keep that path compiled and exercised.
 */
public class PersonAddressDto {

    private String name;

    @Column("address_id")
    private UUID addressId;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getAddressId() {
        return addressId;
    }

    public void setAddressId(UUID addressId) {
        this.addressId = addressId;
    }
}
