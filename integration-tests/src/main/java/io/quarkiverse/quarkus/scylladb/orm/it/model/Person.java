package io.quarkiverse.quarkus.scylladb.orm.it.model;

import java.util.UUID;

import io.quarkiverse.quarkus.scylladb.orm.enums.ReturnType;
import io.quarkiverse.quarkus.scylladb.orm.mapping.*;

@Table("person")
@Queries({
        @Query(name = "findAddressRef", cql = "SELECT name, address_id FROM person WHERE id = :id", returnType = ReturnType.SINGLE, resultClass = PersonAddressRef.class),
        @Query(name = "findAddressDto", cql = "SELECT name, address_id FROM person WHERE id = :id", returnType = ReturnType.SINGLE, resultClass = PersonAddressDto.class)
})
@GenerateRepository(GenerateRepository.RepositoryType.BOTH)
public class Person {

    @PartitionKey
    @GeneratedValue(strategy = GeneratedValue.Strategy.UUID)
    private UUID id;

    @Column("name")
    private String name;

    @Column("address_id")
    private UUID addressId;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

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
