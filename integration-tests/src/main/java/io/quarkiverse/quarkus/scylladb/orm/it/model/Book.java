package io.quarkiverse.quarkus.scylladb.orm.it.model;

import java.util.UUID;

import io.quarkiverse.quarkus.scylladb.orm.enums.ReturnType;
import io.quarkiverse.quarkus.scylladb.orm.mapping.*;

@Table("book")
@Queries({
        @Query(name = "findByTitle", cql = "SELECT * FROM book WHERE title = :title LIMIT 1", returnType = ReturnType.SINGLE, paramTypes = {
                @Query.Param(name = "title", type = String.class)
        }),
        @Query(name = "deleteAll", cql = "TRUNCATE book", returnType = ReturnType.VOID),
        @Query(name = "findAllByTitle", cql = "SELECT * FROM book WHERE title = :title", paramTypes = @Query.Param(name = "title", type = String.class), returnType = ReturnType.LIST),
        @Query(name = "touchAndReturn", cql = "UPDATE book SET active = true WHERE id = :id IF EXISTS", returnType = ReturnType.SINGLE)
})
@GenerateRepository(GenerateRepository.RepositoryType.BOTH)
public class Book {

    @PartitionKey
    @GeneratedValue(strategy = GeneratedValue.Strategy.UUID)
    private UUID id;

    @Column("title")
    private String title;

    @Column("active")
    private boolean active;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
