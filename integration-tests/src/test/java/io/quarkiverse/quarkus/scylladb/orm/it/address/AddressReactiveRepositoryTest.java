package io.quarkiverse.quarkus.scylladb.orm.it.address;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.*;

import io.quarkiverse.quarkus.scylladb.orm.it.model.Address;
import io.quarkiverse.quarkus.scylladb.orm.it.model.AddressBaseReactiveRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.util.ScyllaDbTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;

@QuarkusTest
@QuarkusTestResource(ScyllaDbTestResource.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AddressReactiveRepositoryTest {

    @Inject
    AddressBaseReactiveRepository addressRepository;

    private static UUID createdId;

    @Test
    @Order(1)
    void testCreate() {
        Address address = new Address();
        address.setId(UUID.randomUUID());
        address.setStreet("Reactive Street");
        address.setHousenumber("7");

        Address saved = addressRepository.save(address)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNotNull(saved);
        assertNotNull(saved.getId());
        assertEquals("Reactive Street", saved.getStreet());
        assertEquals("7", saved.getHousenumber());

        createdId = saved.getId();
    }

    @Test
    @Order(2)
    void testFindById() {
        Address found = addressRepository.findById(createdId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNotNull(found);
        assertEquals("Reactive Street", found.getStreet());
    }

    @Test
    @Order(3)
    void testExistsById() {
        Boolean exists = addressRepository.existsById(createdId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertTrue(exists);

        Boolean notExists = addressRepository.existsById(UUID.randomUUID())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertFalse(notExists);
    }

    @Test
    @Order(4)
    void testUpdate() {
        Address address = addressRepository.findById(createdId).await().indefinitely();
        address.setStreet("Updated Reactive");

        Address updated = addressRepository.update(address)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertEquals("Updated Reactive", updated.getStreet());
    }

    @Test
    @Order(5)
    void testFindAll() {
        AssertSubscriber<Address> subscriber = addressRepository.findAll()
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        List<Address> all = subscriber.awaitCompletion().assertCompleted().getItems();

        assertNotNull(all);
        assertFalse(all.isEmpty());
    }

    @Test
    @Order(6)
    void testCount() {
        Long count = addressRepository.count()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertTrue(count >= 1);
    }

    @Test
    @Order(7)
    void testSaveWithGeneratedUuid() {
        Address address = new Address();
        address.setStreet("AutoReactive");
        address.setHousenumber("1");

        Address saved = addressRepository.save(address)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNotNull(saved.getId(), "UUID should be auto-generated");
        assertEquals("AutoReactive", saved.getStreet());

        addressRepository.deleteById(saved.getId()).await().indefinitely();
    }

    @Test
    @Order(8)
    void testDelete() {
        addressRepository.deleteById(createdId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted();

        Boolean exists = addressRepository.existsById(createdId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertFalse(exists);
    }
}
