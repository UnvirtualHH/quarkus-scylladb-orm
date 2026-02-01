package io.quarkiverse.quarkus.scylladb.orm.it.address;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.*;

import io.quarkiverse.quarkus.scylladb.orm.it.model.Address;
import io.quarkiverse.quarkus.scylladb.orm.it.model.AddressBaseRepository;
import io.quarkiverse.quarkus.scylladb.orm.it.util.ScyllaDbTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(ScyllaDbTestResource.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AddressRepositoryTest {

    @Inject
    AddressBaseRepository addressRepository;

    private static UUID createdId;

    @Test
    @Order(1)
    void testCreate() {
        Address address = new Address();
        address.setId(UUID.randomUUID());
        address.setStreet("Main Street");
        address.setHousenumber("42");

        Address saved = addressRepository.save(address);

        assertNotNull(saved);
        assertNotNull(saved.getId());
        assertEquals("Main Street", saved.getStreet());
        assertEquals("42", saved.getHousenumber());

        createdId = saved.getId();
    }

    @Test
    @Order(2)
    void testFindById() {
        Address found = addressRepository.findById(createdId);

        assertNotNull(found);
        assertEquals("Main Street", found.getStreet());
        assertEquals("42", found.getHousenumber());
    }

    @Test
    @Order(3)
    void testExistsById() {
        assertTrue(addressRepository.existsById(createdId));
        assertFalse(addressRepository.existsById(UUID.randomUUID()));
    }

    @Test
    @Order(4)
    void testUpdate() {
        Address address = addressRepository.findById(createdId);
        address.setStreet("Updated Street");
        address.setHousenumber("99");

        Address updated = addressRepository.update(address);

        assertEquals("Updated Street", updated.getStreet());
        assertEquals("99", updated.getHousenumber());
    }

    @Test
    @Order(5)
    void testFindAll() {
        List<Address> all = addressRepository.findAll();

        assertNotNull(all);
        assertFalse(all.isEmpty());
    }

    @Test
    @Order(6)
    void testCount() {
        long count = addressRepository.count();

        assertTrue(count >= 1);
    }

    @Test
    @Order(7)
    void testSaveWithGeneratedUuid() {
        Address address = new Address();
        address.setStreet("AutoStreet");
        address.setHousenumber("1");

        Address saved = addressRepository.save(address);

        assertNotNull(saved.getId(), "UUID should be auto-generated");
        assertEquals("AutoStreet", saved.getStreet());

        addressRepository.deleteById(saved.getId());
    }

    @Test
    @Order(8)
    void testDelete() {
        addressRepository.deleteById(createdId);

        assertFalse(addressRepository.existsById(createdId));
    }
}
