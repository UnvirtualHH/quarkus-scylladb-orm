package io.quarkiverse.quarkus.scylladb.orm.repository;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkiverse.quarkus.scylladb.orm.repository.util.Sortable;

/**
 * The guard that turns an impossible {@code ORDER BY} into a client-side error.
 * <p>
 * {@code findAll(Pageable, Sortable)} scans the whole table, and CQL only allows
 * {@code ORDER BY} once the partition key is restricted — so the sortable argument could
 * never do anything except make the server reject the statement. The only call that ever
 * worked passed {@code null}, which is exactly what the extension's own tests did, so
 * nothing noticed.
 */
class EntityStatementsTest {

    @Test
    @DisplayName("no sortable is the only thing a full scan can accept")
    void nullAndEmptySortablesPass() {
        assertDoesNotThrow(() -> EntityStatements.rejectSortOnUnrestrictedScan(null, "findAll"));
        assertDoesNotThrow(() -> EntityStatements.rejectSortOnUnrestrictedScan(new Sortable(null, true), "findAll"));
        assertDoesNotThrow(() -> EntityStatements.rejectSortOnUnrestrictedScan(new Sortable("", true), "findAll"));
    }

    @Test
    @DisplayName("a real sortable is rejected, naming the column and the way out")
    void aSortableOnAFullScanIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EntityStatements.rejectSortOnUnrestrictedScan(Sortable.asc("created_at"), "findAll"));

        assertTrue(e.getMessage().contains("created_at"), e.getMessage());
        assertTrue(e.getMessage().contains("queryPaged"), "the message must point at the API that can sort: "
                + e.getMessage());
    }

    @Test
    @DisplayName("the method name is carried into the message")
    void theMessageNamesTheCaller() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EntityStatements.rejectSortOnUnrestrictedScan(Sortable.desc("id"), "findAllPaged"));

        assertTrue(e.getMessage().startsWith("findAllPaged"), e.getMessage());
    }
}
