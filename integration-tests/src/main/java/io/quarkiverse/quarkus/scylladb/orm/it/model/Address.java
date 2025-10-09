package io.quarkiverse.quarkus.scylladb.orm.it.model;

import java.util.UUID;

import io.quarkiverse.quarkus.scylladb.orm.mapping.*;

@Table("address")
@GenerateRepository(GenerateRepository.RepositoryType.BOTH)
public class Address {

    @PartitionKey
    @GeneratedValue(strategy = GeneratedValue.Strategy.UUID)
    private UUID id;

    @Column("street")
    private String street;

    @Column("housenumber")
    private String housenumber;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getStreet() {
        return street;
    }

    public void setStreet(String street) {
        this.street = street;
    }

    public String getHousenumber() {
        return housenumber;
    }

    public void setHousenumber(String housenumber) {
        this.housenumber = housenumber;
    }
}
