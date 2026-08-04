package io.quarkiverse.quarkus.scylladb.orm.it.person;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.*;

import io.quarkiverse.quarkus.scylladb.orm.it.model.Person;
import io.quarkiverse.quarkus.scylladb.orm.it.model.PersonBaseReactiveRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.util.ScyllaDbTestResource;
import io.quarkiverse.quarkus.scylladb.orm.repository.util.Pageable;
import io.quarkiverse.quarkus.scylladb.orm.repository.util.Paged;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;

@QuarkusTest
@QuarkusTestResource(ScyllaDbTestResource.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PersonReactiveRepositoryTest {

    private static UUID createdId;
    private static UUID secondId;

    @Inject
    PersonBaseReactiveRepository personRepository;

    @Test
    @Order(1)
    void testCreate() {
        Person person = new Person();
        person.setId(UUID.randomUUID());
        person.setName("Tester");
        person.setAddressId(UUID.randomUUID());

        Person created = personRepository.save(person)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNotNull(created.getId());
        assertEquals("Tester", created.getName());

        createdId = created.getId();
    }

    @Test
    @Order(2)
    void testCreateSecond() {
        Person person = new Person();
        person.setId(UUID.randomUUID());
        person.setName("SecondPerson");
        person.setAddressId(UUID.randomUUID());

        Person created = personRepository.save(person)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNotNull(created.getId());
        secondId = created.getId();
    }

    @Test
    @Order(3)
    void testFindById() {
        Person found = personRepository.findById(createdId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNotNull(found);
        assertEquals("Tester", found.getName());
    }

    @Test
    @Order(4)
    void testFindByIdNotFound() {
        Person found = personRepository.findById(UUID.randomUUID())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNull(found);
    }

    @Test
    @Order(5)
    void testFindByKeys() {
        Person found = personRepository.findByKeys(createdId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNotNull(found);
        assertEquals("Tester", found.getName());
    }

    @Test
    @Order(6)
    void testUpdate() {
        Person person = personRepository.findById(createdId)
                .await().indefinitely();
        person.setName("Updated");

        Person updated = personRepository.update(person)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertEquals("Updated", updated.getName());
    }

    @Test
    @Order(7)
    void testExists() {
        Boolean existsById = personRepository.existsById(createdId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertTrue(existsById);

        Boolean notExists = personRepository.existsById(UUID.randomUUID())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertFalse(notExists);

        Person person = personRepository.findById(createdId).await().indefinitely();

        Boolean exists = personRepository.exists(person)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertTrue(exists);
    }

    @Test
    @Order(8)
    void testQueryByName() {
        AssertSubscriber<Person> subscriber = personRepository
                .query("SELECT * FROM person WHERE name = :name", Map.of("name", "Updated"))
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        List<Person> list = subscriber
                .awaitItems(1)
                .assertCompleted()
                .getItems();

        assertEquals(1, list.size());
        assertEquals("Updated", list.get(0).getName());
    }

    @Test
    @Order(9)
    void testFindAllAndCount() {
        AssertSubscriber<Person> multiSub = personRepository.findAll()
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        multiSub
                .awaitCompletion()
                .assertCompleted();

        List<Person> all = multiSub.getItems();
        assertFalse(all.isEmpty());
        assertTrue(all.size() >= 2);

        long count = personRepository.count()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted()
                .getItem();

        assertEquals(all.size(), count);
    }

    @Test
    @Order(10)
    void testMerge() {
        Person person = new Person();
        person.setId(createdId);
        person.setName("Merged");
        person.setAddressId(UUID.randomUUID());

        Person merged = personRepository.merge(person)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertEquals("Merged", merged.getName());
    }

    @Test
    @Order(11)
    void testFindAllPaged() {
        Pageable pageable = new Pageable(1, null);
        Paged<Person> page1 = personRepository.findAllPaged(pageable, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNotNull(page1);
        assertEquals(1, page1.content().size());
        // Unconditional — see the note on the blocking equivalent.
        assertTrue(page1.hasNextPage(), "expected a paging state while more rows remain");

        Pageable pageable2 = new Pageable(1, page1.nextPagingState());
        Paged<Person> page2 = personRepository.findAllPaged(pageable2, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNotNull(page2);
        assertFalse(page2.content().isEmpty());
        assertNotEquals(
                page1.content().get(0).getId(),
                page2.content().get(0).getId());
    }

    @Test
    @Order(12)
    void testFindAllWithPageable() {
        Pageable pageable = new Pageable(10, null);
        AssertSubscriber<Person> subscriber = personRepository.findAll(pageable, null)
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        List<Person> results = subscriber.awaitCompletion().assertCompleted().getItems();

        assertNotNull(results);
        assertFalse(results.isEmpty());
    }

    @Test
    @Order(13)
    void testDeleteById() {
        personRepository.deleteById(secondId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted();

        Boolean exists = personRepository.existsById(secondId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertFalse(exists);
    }

    @Test
    @Order(14)
    void testDeleteByKeys() {
        Person temp = new Person();
        temp.setId(UUID.randomUUID());
        temp.setName("ToDeleteByKeys");
        temp.setAddressId(UUID.randomUUID());

        Person saved = personRepository.save(temp)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        personRepository.deleteByKeys(saved.getId())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted();

        Boolean exists = personRepository.existsById(saved.getId())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertFalse(exists);
    }

    @Test
    @Order(15)
    void testDeleteByEntity() {
        Person person = personRepository.findById(createdId).await().indefinitely();

        personRepository.delete(person)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted();

        Boolean exists = personRepository.existsById(createdId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertFalse(exists);
    }

    @Test
    @Order(16)
    void testSaveWithGeneratedUuid() {
        Person person = new Person();
        person.setName("AutoUUID");

        Person saved = personRepository.save(person)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNotNull(saved.getId(), "UUID should be auto-generated");
        assertEquals("AutoUUID", saved.getName());

        personRepository.deleteById(saved.getId())
                .await().indefinitely();
    }
}
