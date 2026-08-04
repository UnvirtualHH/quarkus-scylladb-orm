package io.quarkiverse.quarkus.scylladb.orm.processor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts on the source the mapper generator actually emits. Until now this code was
 * only validated by "the generated code compiles and the integration tests pass", which
 * says nothing about <em>what</em> is emitted and needs a database to say even that.
 */
class MapperGenerationTest {

    private static final String PKG = "test.model";

    private static GeneratedSources.Result compile(String body) {
        return GeneratedSources.compile(PKG + ".Sample", """
                package test.model;

                import java.util.*;
                import java.time.*;
                import io.quarkiverse.quarkus.scylladb.orm.mapping.*;
                import io.quarkiverse.quarkus.scylladb.orm.enums.*;

                @Table("sample")
                public class Sample {
                %s
                }
                """.formatted(body));
    }

    private static String mapper(String body) {
        GeneratedSources.Result result = compile(body);
        assertTrue(result.success(), result.errorText());
        return result.source(PKG + ".SampleMapper");
    }

    @Test
    void columnConstantListsEveryMappedColumnInDeclarationOrder() {
        String source = mapper("""
                    @PartitionKey private UUID id;
                    @Column("full_name") private String name;
                    private int age;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                    public int getAge() { return age; }
                    public void setAge(int age) { this.age = age; }
                """);

        assertTrue(source.contains("COLUMN_NAMES = {\"id\", \"full_name\", \"age\"}"), source);
    }

    @Test
    void transientFieldsAreExcludedEverywhere() {
        String source = mapper("""
                    @PartitionKey private UUID id;
                    @Transient private String scratch;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getScratch() { return scratch; }
                    public void setScratch(String s) { this.scratch = s; }
                """);

        assertFalse(source.contains("scratch"), "@Transient field leaked into the mapper:\n" + source);
        assertFalse(source.contains("getScratch"), source);
    }

    @Test
    void inheritedFieldsAreMapped() {
        GeneratedSources.Result result = GeneratedSources.compile(Map.of(
                PKG + ".Base", """
                        package test.model;
                        import io.quarkiverse.quarkus.scylladb.orm.mapping.*;
                        public abstract class Base {
                            @Column("created_by") private String createdBy;
                            public String getCreatedBy() { return createdBy; }
                            public void setCreatedBy(String v) { this.createdBy = v; }
                        }
                        """,
                PKG + ".Sample", """
                        package test.model;
                        import java.util.UUID;
                        import io.quarkiverse.quarkus.scylladb.orm.mapping.*;
                        @Table("sample")
                        public class Sample extends Base {
                            @PartitionKey private UUID id;
                            public UUID getId() { return id; }
                            public void setId(UUID id) { this.id = id; }
                        }
                        """));

        assertTrue(result.success(), result.errorText());
        assertTrue(result.source(PKG + ".SampleMapper").contains("created_by"),
                "inherited column missing from the generated mapper");
    }

    @Test
    @DisplayName("composite keys keep their declared ordinal order")
    void compositeKeysAreOrderedByOrdinal() {
        // Declared out of order on purpose: the generator must sort by ordinal, not by
        // declaration, or the WHERE clause and the bound values would disagree.
        String source = mapper("""
                    @ClusteringKey(ordinal = 1) private UUID second;
                    @PartitionKey(ordinal = 1) private UUID pkSecond;
                    @ClusteringKey(ordinal = 0) private UUID first;
                    @PartitionKey(ordinal = 0) private String pkFirst;
                    public UUID getSecond() { return second; }
                    public void setSecond(UUID v) { this.second = v; }
                    public UUID getPkSecond() { return pkSecond; }
                    public void setPkSecond(UUID v) { this.pkSecond = v; }
                    public UUID getFirst() { return first; }
                    public void setFirst(UUID v) { this.first = v; }
                    public String getPkFirst() { return pkFirst; }
                    public void setPkFirst(String v) { this.pkFirst = v; }
                """);

        assertTrue(source.contains("PK_NAMES = {\"pkFirst\", \"pkSecond\"}"), source);
        assertTrue(source.contains("CK_NAMES = {\"first\", \"second\"}"), source);
        assertTrue(source.indexOf("KeyComponent.of(\"pkFirst\"") < source.indexOf("KeyComponent.of(\"pkSecond\""),
                source);
    }

    @Test
    void generatedValueAssignsAUuidOnlyWhenAbsent() {
        String source = mapper("""
                    @PartitionKey @GeneratedValue private UUID id;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                """);

        assertTrue(source.contains("if (entity.getId() == null)"), source);
        assertTrue(source.contains("entity.setId(UUID.randomUUID())"), source);
    }

    @Test
    void booleanGettersUseTheIsPrefix() {
        String source = mapper("""
                    @PartitionKey private UUID id;
                    private boolean active;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public boolean isActive() { return active; }
                    public void setActive(boolean v) { this.active = v; }
                """);

        assertTrue(source.contains("entity.isActive()"), source);
        assertFalse(source.contains("entity.getActive()"), source);
    }

    @Test
    void primitivesAreWrittenUnconditionallyAndObjectsOnlyWhenPresent() {
        // A primitive has no null state, so guarding it would mean never writing a 0.
        String source = mapper("""
                    @PartitionKey private UUID id;
                    private int count;
                    private String note;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public int getCount() { return count; }
                    public void setCount(int v) { this.count = v; }
                    public String getNote() { return note; }
                    public void setNote(String v) { this.note = v; }
                """);

        assertTrue(source.contains("props.put(\"count\", entity.getCount())"), source);
        assertTrue(source.contains("if (entity.getNote() != null)"), source);
    }

    @Test
    void collectionsUseTypedAccessorsRatherThanGenericType() {
        // Typed accessors keep the mapper reflection-free, which native image needs.
        String source = mapper("""
                    @PartitionKey private UUID id;
                    private List<String> tags;
                    private Set<Integer> scores;
                    private Map<String, String> attrs;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public List<String> getTags() { return tags; }
                    public void setTags(List<String> v) { this.tags = v; }
                    public Set<Integer> getScores() { return scores; }
                    public void setScores(Set<Integer> v) { this.scores = v; }
                    public Map<String, String> getAttrs() { return attrs; }
                    public void setAttrs(Map<String, String> v) { this.attrs = v; }
                """);

        assertTrue(source.contains("row.getList(\"tags\", String.class)"), source);
        assertTrue(source.contains("row.getSet(\"scores\", Integer.class)"), source);
        assertTrue(source.contains("row.getMap(\"attrs\", String.class, String.class)"), source);
        assertFalse(source.contains("GenericType.of(java.util.List"), source);
    }

    @Test
    void mappersAreNotFinalSoArcCanProxyThemWithoutBytecodeRewriting() {
        String source = mapper("""
                    @PartitionKey private UUID id;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                """);

        assertTrue(source.contains("public class SampleMapper"), source);
        assertFalse(source.contains("public final class SampleMapper"), source);
    }
}
