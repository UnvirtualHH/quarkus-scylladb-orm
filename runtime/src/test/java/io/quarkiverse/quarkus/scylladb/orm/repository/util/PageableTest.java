package io.quarkiverse.quarkus.scylladb.orm.repository.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * A page size of 0 used to be accepted and handed to the driver, which reads it as
 * "no paging" — so the paged read fetched the whole table in one page.
 */
class PageableTest {

    @Test
    void keepsAValidSize() {
        assertEquals(20, Pageable.ofSize(20).size());
        assertEquals("state", Pageable.of(20, "state").pagingState());
    }

    @Test
    void rejectsAPageSizeThatDisablesPaging() {
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class, () -> Pageable.ofSize(0));
        assertTrue(zero.getMessage().contains("at least 1"), zero.getMessage());

        assertThrows(IllegalArgumentException.class, () -> Pageable.ofSize(-1));
        assertThrows(IllegalArgumentException.class, () -> Pageable.of(0, "state"));
    }
}
