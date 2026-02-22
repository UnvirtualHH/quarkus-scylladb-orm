package io.quarkiverse.quarkus.scylladb.orm.it.model;

import java.util.UUID;

import io.quarkiverse.quarkus.scylladb.orm.enums.ReturnType;
import io.quarkiverse.quarkus.scylladb.orm.mapping.*;

@Table("author")
@Queries({
        @Query(name = "findByIdAndNamePrefix", cql = "SELECT * FROM author WHERE id = :id AND name = :id2 ALLOW FILTERING", returnType = ReturnType.SINGLE, paramTypes = {
                @Query.Param(name = "id", type = UUID.class),
                @Query.Param(name = "id2", type = String.class)
        }),
        @Query(name = "findByThreeParamsPrefix", cql = "SELECT * FROM author WHERE id = :id AND name = :id2 AND name = :id3 ALLOW FILTERING", returnType = ReturnType.SINGLE, paramTypes = {
                @Query.Param(name = "id", type = UUID.class),
                @Query.Param(name = "id2", type = String.class),
                @Query.Param(name = "id3", type = String.class)
        })
})
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
