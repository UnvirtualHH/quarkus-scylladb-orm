package io.quarkiverse.quarkus.scylladb.orm.it.query;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.*;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;

import io.quarkiverse.quarkus.scylladb.orm.it.model.Author;
import io.quarkiverse.quarkus.scylladb.orm.it.model.AuthorBaseReactiveRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.model.Book;
import io.quarkiverse.quarkus.scylladb.orm.it.model.BookBaseReactiveRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.util.ScyllaDbTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;

@QuarkusTest
@QuarkusTestResource(ScyllaDbTestResource.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class QueryReactiveTest {

    @Inject
    CqlSession session;

    @Inject
    BookBaseReactiveRepository bookRepository;

    @Inject
    AuthorBaseReactiveRepository authorRepository;

    @BeforeEach
    public void clearDatabase() {
        session.execute("TRUNCATE book");
        session.execute("TRUNCATE author");
    }

    @Test
    @Order(1)
    void testBookExistsReactive() {
        createTestBook("Book 1");

        Book byTitle = bookRepository.findByTitle("Book 1")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNotNull(byTitle);
        assertEquals("Book 1", byTitle.getTitle());
    }

    @Test
    @Order(2)
    void testChangeBookExistsReactive() {
        UUID testBook = createTestBook("Book 1");

        Book book = bookRepository.touchAndReturn(testBook)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNotNull(book);
    }

    @Test
    @Order(3)
    void testFindAllByTitleReactive() {
        createTestBook("SameTitle");
        createTestBook("SameTitle");
        createTestBook("DifferentTitle");

        AssertSubscriber<Book> subscriber = bookRepository.findAllByTitle("SameTitle")
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        List<Book> results = subscriber.awaitCompletion().assertCompleted().getItems();

        assertNotNull(results);
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(b -> "SameTitle".equals(b.getTitle())));
    }

    @Test
    @Order(4)
    void testFindAllByTitleEmptyReactive() {
        createTestBook("Existing");

        AssertSubscriber<Book> subscriber = bookRepository.findAllByTitle("NonExistent")
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        List<Book> results = subscriber.awaitCompletion().assertCompleted().getItems();

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    @Order(5)
    void testFindByTitleNotFoundReactive() {
        Book byTitle = bookRepository.findByTitle("NoSuchBook")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNull(byTitle);
    }

    @Test
    @Order(6)
    void testDeleteAllReactive() {
        createTestBook("ToDelete1");
        createTestBook("ToDelete2");

        bookRepository.deleteAll()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted();

        AssertSubscriber<Book> subscriber = bookRepository.findAll()
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        List<Book> after = subscriber.awaitCompletion().assertCompleted().getItems();
        assertTrue(after.isEmpty());
    }

    @Test
    @Order(7)
    void testSaveAndFindByIdReactive() {
        Book book = new Book();
        book.setId(UUID.randomUUID());
        book.setTitle("SaveTest");
        book.setActive(true);

        Book saved = bookRepository.save(book)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNotNull(saved);

        Book found = bookRepository.findById(saved.getId())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNotNull(found);
        assertEquals("SaveTest", found.getTitle());
        assertEquals(true, found.getActive());
    }

    @Test
    @Order(8)
    void testUpdateBookReactive() {
        UUID id = createTestBook("Original");

        Book book = bookRepository.findById(id)
                .await().indefinitely();
        assertNotNull(book);
        book.setTitle("Modified");

        Book updated = bookRepository.update(book)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertEquals("Modified", updated.getTitle());
    }

    @Test
    @Order(9)
    void testPrefixParamsTwoArgumentsReactive() {
        UUID id = UUID.randomUUID();
        createTestAuthor(id, "Ada");

        Author found = authorRepository.findByIdAndNamePrefix(id, "Ada")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNotNull(found);
        assertEquals(id, found.getId());
        assertEquals("Ada", found.getName());
    }

    @Test
    @Order(10)
    void testPrefixParamsThreeArgumentsReactive() {
        UUID id = UUID.randomUUID();
        createTestAuthor(id, "Linus");

        Author found = authorRepository.findByThreeParamsPrefix(id, "Linus", "Linus")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNotNull(found);
        assertEquals(id, found.getId());
        assertEquals("Linus", found.getName());
    }

    private UUID createTestBook(String title) {
        UUID bookId = UUID.randomUUID();

        session.execute(SimpleStatement.builder(
                "INSERT INTO book (id, title, active) VALUES (?, ?, ?)")
                .addPositionalValue(bookId)
                .addPositionalValue(title)
                .addPositionalValue(false)
                .build());

        return bookId;
    }

    private void createTestAuthor(UUID id, String name) {
        session.execute(SimpleStatement.builder(
                "INSERT INTO author (id, name) VALUES (?, ?)")
                .addPositionalValue(id)
                .addPositionalValue(name)
                .build());
    }
}
