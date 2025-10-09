package io.quarkiverse.quarkus.scylladb.orm.it.model;

import java.util.UUID;

import io.quarkiverse.quarkus.scylladb.orm.mapping.*;

@Table("author")
@GenerateRepository(GenerateRepository.RepositoryType.BOTH)
public class Author {

    @PartitionKey
    @GeneratedValue(strategy = GeneratedValue.Strategy.UUID)
    private UUID id;

    @Column("name")
    private String name;

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
}
