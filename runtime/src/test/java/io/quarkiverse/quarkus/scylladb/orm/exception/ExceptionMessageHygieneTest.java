package io.quarkiverse.quarkus.scylladb.orm.exception;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Exception messages must describe bind parameters by count and type, never by value:
 * they end up in logs and stack traces, and the values are whatever the application
 * writes — credentials, tokens, personal data. That property came out of the June 2026
 * security review and had no test.
 */
class ExceptionMessageHygieneTest {

    private static final String SECRET = "hunter2-should-never-appear";

    @Test
    void queryExceptionsDescribeParametersWithoutRevealingThem() {
        ScyllaQueryException error = new ScyllaQueryException("person",
                "SELECT * FROM person WHERE token = ?",
                new Object[] { SECRET, UUID.randomUUID(), 42 },
                new IllegalStateException("boom"));

        assertFalse(error.getMessage().contains(SECRET), error.getMessage());
        assertTrue(error.getMessage().contains("3 param(s)"), error.getMessage());
        assertTrue(error.getMessage().contains("String"), error.getMessage());
        assertTrue(error.getMessage().contains("person"), error.getMessage());
        assertEquals("person", error.getTable());
        assertEquals("SELECT * FROM person WHERE token = ?", error.getCql());
    }

    @Test
    void writeExceptionsApplyTheSameRule() {
        ScyllaWriteException error = new ScyllaWriteException("person",
                "INSERT INTO person (token) VALUES (?)",
                new Object[] { SECRET },
                new IllegalStateException("boom"));

        assertFalse(error.getMessage().contains(SECRET), error.getMessage());
        assertTrue(error.getMessage().contains("1 param(s)"), error.getMessage());
    }

    @Test
    void namedParametersAreReducedToACount() {
        // A map of named params must not have its keys or values spelled out either.
        ScyllaQueryException error = new ScyllaQueryException("person", "SELECT ...",
                new Object[] { Map.of("token", SECRET, "email", "a@b.c") },
                null);

        assertFalse(error.getMessage().contains(SECRET), error.getMessage());
        assertFalse(error.getMessage().contains("a@b.c"), error.getMessage());
        assertTrue(error.getMessage().contains("2 named param(s)"), error.getMessage());
    }

    @Test
    void collectionValuesDoNotLeakThroughToString() {
        ScyllaQueryException error = new ScyllaQueryException("person", "SELECT ...",
                new Object[] { List.of(SECRET) }, null);

        assertFalse(error.getMessage().contains(SECRET), error.getMessage());
    }

    @Test
    void nullAndEmptyParameterListsAreHandled() {
        assertTrue(new ScyllaQueryException("t", "SELECT 1", null, null).getMessage().contains("none"));
        assertTrue(new ScyllaQueryException("t", "SELECT 1", new Object[0], null).getMessage().contains("0 param(s)"));
        assertTrue(new ScyllaQueryException("t", "SELECT 1", new Object[] { null }, null).getMessage()
                .contains("null"));
    }

    @Test
    void mappingExceptionsCarryTableAndCause() {
        IllegalStateException cause = new IllegalStateException("bad row");
        ScyllaMappingException error = new ScyllaMappingException("person", "Failed to map row", cause);

        assertTrue(error.getMessage().contains("person"), error.getMessage());
        assertSame(cause, error.getCause());
    }
}
