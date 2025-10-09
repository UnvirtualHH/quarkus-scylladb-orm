package io.quarkiverse.quarkus.scylladb.orm.it.person;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkiverse.quarkus.scylladb.orm.it.model.Person;
import io.quarkiverse.quarkus.scylladb.orm.it.model.PersonBaseReactiveRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.util.ScyllaDbTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;

@QuarkusTest
@QuarkusTestResource(ScyllaDbTestResource.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PersonReactiveRepositoryTest {

    private static UUID createdId;

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
    void testFindById() {
        Person found = personRepository.findById(createdId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNotNull(found);
        assertEquals("Tester", found.getName());
    }

    @Test
    @Order(3)
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
    @Order(4)
    void testExists() {
        Boolean existsById = personRepository.existsById(createdId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertTrue(existsById);

        Person person = personRepository.findById(createdId).await().indefinitely();

        Boolean exists = personRepository.exists(person)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertTrue(exists);
    }

    @Test
    @Order(5)
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
    @Order(6)
    void testFindAllAndCount() {
        AssertSubscriber<Person> multiSub = personRepository.findAll()
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        multiSub
                .awaitCompletion()
                .assertCompleted();

        List<Person> all = multiSub.getItems();
        assertFalse(all.isEmpty());

        long count = personRepository.count()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted()
                .getItem();

        assertEquals(all.size(), count);
    }

    @Test
    @Order(7)
    void testMerge() {
        Person person = new Person();
        person.setId(createdId);
        person.setName("Merged");

        Person merged = personRepository.merge(person)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertEquals("Merged", merged.getName());
    }

    @Test
    @Order(8)
    void testDelete() {
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
}
