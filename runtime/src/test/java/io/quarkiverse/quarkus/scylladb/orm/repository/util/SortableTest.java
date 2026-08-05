package io.quarkiverse.quarkus.scylladb.orm.repository.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link Sortable} interpolates its column name straight into the CQL, so its validation
 * is the only thing standing between a caller-supplied string and the statement. It had
 * no tests at all.
 */
class SortableTest {

    @Test
    void ascAndDescRenderTheExpectedClause() {
        assertEquals("ORDER BY created_at ASC", Sortable.asc("created_at").toCql());
        assertEquals("ORDER BY created_at DESC", Sortable.desc("created_at").toCql());
    }

    @Test
    void anAbsentColumnRendersNothing() {
        // findAll(pageable, sortable) splices the result in unconditionally, so an empty
        // column has to produce an empty string rather than a dangling ORDER BY.
        assertEquals("", new Sortable(null, true).toCql());
        assertEquals("", new Sortable("", true).toCql());
    }

    @Test
    void plainIdentifiersAreAccepted() {
        assertDoesNotThrow(() -> Sortable.asc("created_at"));
        assertDoesNotThrow(() -> Sortable.asc("_private"));
        assertDoesNotThrow(() -> Sortable.asc("col123"));
        assertDoesNotThrow(() -> Sortable.asc("A"));
    }

    @DisplayName("injection attempts are rejected at construction")
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "created_at; DROP TABLE person",
            "created_at ASC, name",
            "created_at--",
            "created_at'",
            "created_at\"",
            "created_at)",
            "1=1",
            "created at",
            "*",
            "created_at OR 1=1",
            "token(id)",
            "9lives"
    })
    void rejectsAnythingThatIsNotAPlainIdentifier(String column) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Sortable.asc(column));

        assertTrue(error.getMessage().contains(column), error.getMessage());
    }

    @Test
    void validationAlsoAppliesToTheCanonicalConstructor() {
        // asc()/desc() are convenience; nothing stops a caller using the record directly.
        assertThrows(IllegalArgumentException.class, () -> new Sortable("a; DROP TABLE t", false));
    }
}
