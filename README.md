# Quarkus ScyllaDB ORM Extension

A high-performance Quarkus extension for ScyllaDB/Cassandra that provides annotation-based entity mapping, automatic repository generation, and full reactive support via SmallRye Mutiny.

[![Maven Central](https://img.shields.io/maven-central/v/de.prgrm.quarkus-scylladb-orm/quarkus-scylladb-orm)](https://central.sonatype.com/artifact/de.prgrm.quarkus-scylladb-orm/quarkus-scylladb-orm)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## Features

- **Annotation-based entity mapping** - Define entities with `@Table`, `@PartitionKey`, `@ClusteringKey`, etc.
- **Automatic repository generation** - Generate blocking and/or reactive repositories at build time
- **Full reactive support** - First-class Mutiny integration with `Uni<T>` and `Multi<T>`
- **Custom queries** - Define CQL queries with named parameters via `@Query`
- **Pagination** - Token-based paging optimized for ScyllaDB
- **Prepared statement caching** - Automatic caching for optimal performance
- **Type conversion** - Built-in converters with custom converter support
- **CDI integration** - Inject repositories directly into your beans

## Requirements

- Java 21+
- Quarkus 3.x
- ScyllaDB or Apache Cassandra

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>de.prgrm.quarkus-scylladb-orm</groupId>
    <artifactId>quarkus-scylladb-orm</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick Start

### 1. Define Your Entity

```java
import io.quarkiverse.quarkus.scylladb.orm.mapping.*;

@Table("person")
@GenerateRepository
public class Person {

    @PartitionKey
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column("full_name")
    private String name;

    @Column
    private int age;

    // Getters and setters
}
```

### 2. Inject and Use the Repository

```java
import jakarta.inject.Inject;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PersonService {

    @Inject
    PersonBaseRepository personRepository;

    public Person createPerson(String name, int age) {
        Person person = new Person();
        person.setName(name);
        person.setAge(age);
        return personRepository.save(person);  // UUID auto-generated
    }

    public Person findById(UUID id) {
        return personRepository.findById(id);
    }

    public List<Person> findAll() {
        return personRepository.findAll();
    }
}
```

### 3. Reactive Usage

```java
@ApplicationScoped
public class PersonReactiveService {

    @Inject
    PersonBaseReactiveRepository personRepository;

    public Uni<Person> createPerson(String name) {
        Person person = new Person();
        person.setName(name);
        return personRepository.save(person);
    }

    public Multi<Person> streamAll() {
        return personRepository.findAll();
    }
}
```

## Entity Mapping

### Annotations

| Annotation | Description |
|------------|-------------|
| `@Table("name")` | Maps class to a table |
| `@PartitionKey` | Marks field as partition key (use `ordinal` for composite keys) |
| `@ClusteringKey` | Marks field as clustering key (supports `ordinal` and `order`) |
| `@Column("name")` | Maps field to column (optional, defaults to field name) |
| `@GeneratedValue` | Auto-generates values (UUID or SEQUENCE) |
| `@Transient` | Excludes field from persistence |
| `@Enumerated` | Enum handling (STRING or ORDINAL) |
| `@Convert` | Custom type conversion |

### Composite Primary Keys

```java
@Table("sensor_data")
@GenerateRepository
public class SensorData {

    @PartitionKey(ordinal = 0)
    private String sensorId;

    @PartitionKey(ordinal = 1)
    private String region;

    @ClusteringKey(ordinal = 0, order = ClusteringOrder.DESC)
    private Instant timestamp;

    @Column
    private double value;
}
```

Access with composite keys:

```java
// Find by full primary key (partition + clustering)
SensorData data = repository.findByKeys("sensor-1", "us-east", timestamp);

// Delete by full primary key
repository.deleteByKeys("sensor-1", "us-east", timestamp);
```

## Repository Operations

### Blocking Repository

```java
// CRUD
T save(T entity)
T update(T entity)
T merge(T entity)
void delete(T entity)
void deleteById(ID id)
void deleteByKeys(Object... keys)

// Queries
T findById(ID id)
T findByKeys(Object... keys)
List<T> findAll()
long count()

// Existence
boolean exists(T entity)
boolean existsById(ID id)

// Custom CQL
List<T> query(String cql, Object... params)
T querySingle(String cql, Object... params)
void execute(String cql, Object... params)
```

### Reactive Repository

Same operations returning Mutiny types:
- `Uni<T>` for single results
- `Multi<T>` for collections
- `Uni<Void>` for void operations
- `Uni<Long>` for counts
- `Uni<Boolean>` for existence checks

## Pagination

```java
// First page
Pageable pageable = Pageable.ofSize(20);
Paged<Person> page1 = personRepository.findAllPaged(pageable, null);

// Process results
page1.content().forEach(this::process);

// Next page (if exists)
if (page1.hasNextPage()) {
    Pageable nextPageable = Pageable.of(20, page1.nextPagingState());
    Paged<Person> page2 = personRepository.findAllPaged(nextPageable, null);
}
```

### With Sorting

```java
Sortable sort = Sortable.desc("created_at");
Paged<Person> page = personRepository.findAllPaged(pageable, sort);
```

## Custom Queries

### Using @Query Annotation

```java
@Table("book")
@GenerateRepository
@Queries({
    @Query(
        name = "findByTitle",
        cql = "SELECT * FROM book WHERE title = :title ALLOW FILTERING",
        returnType = Query.ReturnType.SINGLE,
        paramTypes = @Query.Param(name = "title", type = String.class)
    ),
    @Query(
        name = "findAllActive",
        cql = "SELECT * FROM book WHERE active = :active ALLOW FILTERING",
        returnType = Query.ReturnType.LIST,
        paramTypes = @Query.Param(name = "active", type = Boolean.class)
    ),
    @Query(
        name = "deactivateAll",
        cql = "UPDATE book SET active = false WHERE id = :id",
        returnType = Query.ReturnType.VOID,
        paramTypes = @Query.Param(name = "id", type = UUID.class)
    )
})
public class Book {
    @PartitionKey
    private UUID id;
    private String title;
    private boolean active;
}
```

Generated methods:

```java
Book book = bookRepository.findByTitle("Clean Code");
List<Book> activeBooks = bookRepository.findAllActive(true);
bookRepository.deactivateAll(bookId);
```

> **Schema/DDL queries:** By default, `@Query` rejects schema-altering (`CREATE`/`ALTER`/`DROP`)
> and `TRUNCATE` statements at build time, so a least-privilege application role is never
> assumed to hold schema permissions. Opt in explicitly per query when you really need it:
>
> ```java
> @Query(name = "purge", cql = "TRUNCATE book", returnType = ReturnType.VOID, allowSchemaChanges = true)
> ```

### Runtime Custom Queries

```java
// Positional parameters
List<Person> results = repository.query(
    "SELECT * FROM person WHERE age > ? ALLOW FILTERING",
    21
);

// Named parameters
List<Person> results = repository.query(
    "SELECT * FROM person WHERE name = :name AND age > :minAge ALLOW FILTERING",
    Map.of("name", "John", "minAge", 21)
);
```

## Configuration

Configure your ScyllaDB connection in `application.properties`:

### Basic Connection

```properties
# Contact points (required) - comma-separated host:port pairs
quarkus.scylla.contact-points=node1:9042,node2:9042,node3:9042

# Local datacenter (required)
quarkus.scylla.local-datacenter=datacenter1

# Default keyspace (required)
quarkus.scylla.keyspace=my_keyspace
```

### Authentication

```properties
# Plain text authentication
quarkus.scylla.auth.username=cassandra
quarkus.scylla.auth.password=cassandra
```

> **Note:** Username and password must be set **together** — setting only one fails fast
> at startup (rather than silently connecting without authentication). In production,
> inject the password from a secret store (Vault, Kubernetes secret) via environment
> variables rather than committing it to `application.properties`.

### Connection Pool

```properties
# Connections per local host (default: 1)
quarkus.scylla.pool.local-size=2

# Connections per remote host (default: 1)
quarkus.scylla.pool.remote-size=1

# Max requests per connection (default: 1024)
quarkus.scylla.pool.max-requests-per-connection=1024

# Heartbeat interval to keep connections alive (default: 30s)
quarkus.scylla.pool.heartbeat-interval=30s

# Connection initialization timeout (default: 5s)
quarkus.scylla.pool.connection-init-timeout=5s
```

### Request Settings

```properties
# Request timeout (default: 2s)
quarkus.scylla.request.timeout=2s

# Consistency level (default: LOCAL_QUORUM)
# Options: ANY, ONE, TWO, THREE, QUORUM, ALL, LOCAL_QUORUM, EACH_QUORUM, SERIAL, LOCAL_SERIAL, LOCAL_ONE
quarkus.scylla.request.consistency=LOCAL_QUORUM

# Serial consistency for LWT (default: SERIAL)
# Options: SERIAL, LOCAL_SERIAL
quarkus.scylla.request.serial-consistency=SERIAL

# Default page size for queries (default: 5000)
quarkus.scylla.request.page-size=5000
```

### SSL/TLS

```properties
# Enable SSL/TLS (default: false)
quarkus.scylla.ssl.enabled=true

# Truststore for server certificate validation
quarkus.scylla.ssl.truststore-path=/path/to/truststore.jks
quarkus.scylla.ssl.truststore-password=changeit

# Keystore for client certificate authentication (mutual TLS)
quarkus.scylla.ssl.keystore-path=/path/to/keystore.p12
quarkus.scylla.ssl.keystore-password=changeit

# Hostname verification (default: true)
quarkus.scylla.ssl.hostname-validation=true
```

> **Security:** Hostname validation is enforced by the driver's SSL engine. Keep it
> enabled (the default) in production — disabling it leaves TLS connections open to
> man-in-the-middle attacks even when the certificate chain is otherwise valid.

### Schema Agreement

```properties
# Timeout for schema agreement after DDL statements (default: 10s)
quarkus.scylla.schema.agreement-timeout=10s

# Interval between schema agreement checks (default: 200ms)
quarkus.scylla.schema.agreement-interval=200ms

# Warn on schema agreement failure (default: true)
quarkus.scylla.schema.agreement-warn-on-failure=true
```

### Reconnection Policy

```properties
# Exponential reconnection base delay (default: 1s)
quarkus.scylla.reconnection.base-delay=1s

# Exponential reconnection max delay (default: 60s)
quarkus.scylla.reconnection.max-delay=60s
```

### Metrics (Micrometer)

```properties
# Enable driver metrics (default: false). Requires a MeterRegistry bean,
# e.g. add the quarkus-micrometer extension. If enabled without a registry,
# metrics stay off and a warning is logged.
quarkus.scylla.metrics.enabled=true

# Session / node metrics to publish (driver metric ids, comma-separated)
quarkus.scylla.metrics.session-metrics=bytes-sent,bytes-received,connected-nodes,cql-requests,cql-client-timeouts,cql-prepared-cache-size
quarkus.scylla.metrics.node-metrics=pool.open-connections,pool.in-flight,errors.request.unsent,errors.request.aborted,retries.total
```

### Request Throttler (overload protection)

```properties
# Throttler type: none (default) | concurrency | rate
quarkus.scylla.throttler.type=concurrency

# concurrency type:
quarkus.scylla.throttler.max-concurrent-requests=10000
# rate type:
quarkus.scylla.throttler.max-requests-per-second=5000
# both types: requests beyond this queue, then fail fast
quarkus.scylla.throttler.max-queue-size=10000
```

### Complete Configuration Reference

| Property | Description | Default |
|----------|-------------|---------|
| `quarkus.scylla.contact-points` | Comma-separated host:port pairs | *required* |
| `quarkus.scylla.local-datacenter` | Local datacenter name | *required* |
| `quarkus.scylla.keyspace` | Default keyspace | *required* |
| `quarkus.scylla.auth.username` | Authentication username | - |
| `quarkus.scylla.auth.password` | Authentication password | - |
| `quarkus.scylla.pool.local-size` | Connections per local host | `2` |
| `quarkus.scylla.pool.remote-size` | Connections per remote host | `1` |
| `quarkus.scylla.pool.max-requests-per-connection` | Max concurrent requests per connection | `1024` |
| `quarkus.scylla.pool.heartbeat-interval` | Connection heartbeat interval | `30s` |
| `quarkus.scylla.pool.connection-init-timeout` | Connection init timeout | `5s` |
| `quarkus.scylla.request.timeout` | Request timeout | `2s` |
| `quarkus.scylla.request.consistency` | Default consistency level | `LOCAL_QUORUM` |
| `quarkus.scylla.request.serial-consistency` | Serial consistency for LWT | `SERIAL` |
| `quarkus.scylla.request.page-size` | Default page size | `5000` |
| `quarkus.scylla.ssl.enabled` | Enable SSL/TLS | `false` |
| `quarkus.scylla.ssl.truststore-path` | Path to truststore | - |
| `quarkus.scylla.ssl.truststore-password` | Truststore password | - |
| `quarkus.scylla.ssl.keystore-path` | Path to keystore (for mTLS) | - |
| `quarkus.scylla.ssl.keystore-password` | Keystore password | - |
| `quarkus.scylla.ssl.hostname-validation` | Verify server hostname | `true` |
| `quarkus.scylla.schema.agreement-timeout` | Schema agreement timeout | `10s` |
| `quarkus.scylla.schema.agreement-interval` | Schema agreement check interval | `200ms` |
| `quarkus.scylla.schema.agreement-warn-on-failure` | Warn on agreement failure | `true` |
| `quarkus.scylla.reconnection.base-delay` | Reconnection base delay | `1s` |
| `quarkus.scylla.reconnection.max-delay` | Reconnection max delay | `60s` |
| `quarkus.scylla.metrics.enabled` | Enable Micrometer driver metrics | `false` |
| `quarkus.scylla.metrics.session-metrics` | Session metric ids to publish | *(see above)* |
| `quarkus.scylla.metrics.node-metrics` | Per-node metric ids to publish | *(see above)* |
| `quarkus.scylla.throttler.type` | Request throttler: none/concurrency/rate | `none` |
| `quarkus.scylla.throttler.max-concurrent-requests` | Max in-flight (concurrency type) | `10000` |
| `quarkus.scylla.throttler.max-requests-per-second` | Max req/s (rate type) | `5000` |
| `quarkus.scylla.throttler.max-queue-size` | Max queued before rejection | `10000` |

## Security & Production Notes

### Authorization is your application's responsibility
This extension is **not an authorization boundary**. It uses a single `CqlSession` with one
set of credentials (the correct model for high throughput — do *not* open a connection per
end user). Any code that can call a repository method can read/write the entire keyspace.
Per-user / per-tenant access control, row-level security and method-level authorization must
be enforced in your application layer (e.g. Quarkus Security with `@RolesAllowed` on your
services).

### Use a least-privilege database role
Connect with a ScyllaDB role scoped to your keyspace that holds only the grants the app
needs (typically `SELECT` and `MODIFY`) — never a superuser. Grant schema/`TRUNCATE`
permissions only to dedicated migration tooling. The `@Query` DDL guard
(`allowSchemaChanges`) reinforces this by refusing to generate schema/TRUNCATE methods
unless you opt in explicitly.

On startup the extension performs a soft check and logs a **warning** if the connected role
is a superuser (skipped silently if the role cannot read `system_auth` — which is itself good
posture). It never fails startup; it only nudges you toward least privilege.

### Per-service security model (each service only touches what it owns)
"Each microservice may only access its own data" is enforced by **ScyllaDB RBAC**, not by
this ORM. The clean model is **one keyspace per service**, each service connecting with its
**own non-superuser role** that is GRANTed only on that keyspace:

```sql
-- One login role per service, with its own secret-managed password (never a superuser):
CREATE ROLE orders_svc   WITH PASSWORD = '...' AND LOGIN = true;
CREATE ROLE payments_svc WITH PASSWORD = '...' AND LOGIN = true;

-- Scope each role to only its own keyspace:
GRANT SELECT, MODIFY ON KEYSPACE orders   TO orders_svc;
GRANT SELECT, MODIFY ON KEYSPACE payments TO payments_svc;

-- Schema changes run under a separate, higher-privileged migration role — NOT the service role:
CREATE ROLE schema_migrator WITH PASSWORD = '...' AND LOGIN = true;
GRANT CREATE, ALTER, DROP ON KEYSPACE orders TO schema_migrator;
```

Each service then configures **its own** credentials and keyspace (inject the password from a
secret store, not `application.properties`):

```properties
# orders service
quarkus.scylla.keyspace=orders
quarkus.scylla.auth.username=orders_svc
quarkus.scylla.auth.password=${ORDERS_DB_PASSWORD}
```

With this setup, `orders_svc` physically cannot read the `payments` keyspace or alter schema —
the server rejects it, regardless of what the application code attempts. The extension fits
this model directly: one `CqlSession` per service, a compile-time keyspace via
`@Table(keyspace=...)`, and the DDL guard keeping schema rights out of the service role.

**Limitation — no row/column-level RBAC.** ScyllaDB grants are keyspace/table-scoped only.
If a single service serves multiple tenants, DB roles will *not* isolate rows per tenant —
enforce that in the data model (tenant id in the partition key) and in your application layer
(Quarkus Security `@RolesAllowed`). The ORM is not an authorization boundary.

### NULL handling — you cannot unset a column via save/update
`save()`/`update()` only write **non-null** columns. This is intentional (it avoids creating
tombstones in ScyllaDB), but it also means setting a field to `null` does **not** clear the
stored value — the column is left untouched. To actively clear a column, issue an explicit
`UPDATE ... SET col = null` (or `DELETE col`) via `@Query`.

### Upgrading to 1.1.0

Behaviour changes that need no action:

- Built-in reads select an explicit column list instead of `SELECT *`. This removes the
  driver's prepared-statement-invalidation warning and the per-execution metadata
  overhead it forces. Your own `@Query` CQL is untouched — prefer explicit columns there
  too.
- `save()`/`update()` bind a fixed column set and leave null fields *unset*. Semantics are
  unchanged (no tombstones, a null still does not clear a column), but an entity now uses
  one prepared statement instead of up to one per null-pattern.
- `findAllPaged()`/`queryPaged()` **actually paginate now**. They previously appended a
  `LIMIT`, which caps the whole result set, so the server returned no paging state and
  `hasNextPage()` was always `false`. If you worked around this, remove the workaround.

Source-incompatible changes:

| Change | Migration |
|--------|-----------|
| Repositories are typed on their partition key (`Repository<Person, UUID>` instead of `Repository<Person, Object>`) | Fix any call passing a wrongly typed id — it was silently failing at runtime before. Entities with a composite partition key keep `Object`; use `findByKeys`. |
| `Paged` no longer has `totalElements` | It was hard-coded to `-1` on every path. Use `count()` if you really need a total. |
| `query`/`querySingle`/`queryScalar`/`execute`/`queryProjection` lost their 1/2/3-argument overloads | The varargs overload covers them; no call site should need changing. |
| `EntityMapper` gained `getColumnNames()` | Only affects hand-written mappers; generated ones are regenerated. |
| `GeneratedValue.Strategy.SEQUENCE` removed | It was never implemented and silently did nothing. Use `UUID` or assign the value yourself. |

### `@Query` must select every entity column when it maps back to the entity
The generated mapper reads **every** mapped field of the entity from the row. A query
like `SELECT id, name FROM person` with `returnType = LIST`/`SINGLE` therefore fails at
runtime with `IllegalArgumentException: <column> is not a column in this row`. Either
select all columns, or use a projection (`resultClass = MyDto.class`), which maps only
the DTO's own fields.

### Avoid unbounded scans on hot paths
`findAll()` (no paging) and `count()` perform cluster-wide scans that will time out and
overload coordinators on large tables. Use `findAll(Pageable, Sortable)`, partition-scoped
`@Query` methods, or a maintained counter table instead.

### Retries / idempotency
Read statements are marked idempotent, so the driver may safely retry them and use
speculative execution. Writes are **not** marked idempotent (lightweight transactions and
counter updates must not be blindly retried) — your application is responsible for deciding
whether and how to retry a failed write, and for making the operation safe to repeat.

### Prepared-statement cache and partial inserts
Because `save()`/`update()` build the CQL from the **non-null** columns of each entity,
entities written with widely varying sets of populated fields produce many distinct CQL
strings, each prepared and cached separately (client- and server-side). For entities with
many optional fields written at high volume, prefer populating a stable set of columns, or
use an explicit `@Query` with a fixed column list, to keep the prepared-statement cache small.

### Shard-aware driver
This extension uses the ScyllaDB shard-aware fork of the Java driver
(`com.scylladb:java-driver-core`), which routes each request to the owning shard. It is
API-compatible with the DataStax/Apache driver (same `com.datastax.oss.driver` packages).

### Observability
Enable `quarkus.scylla.metrics.enabled=true` together with the `quarkus-micrometer`
extension to publish driver request-latency, error and connection-pool metrics — essential
for operating at sustained write volume. Consider a request throttler
(`quarkus.scylla.throttler.type`) as overload protection during incidents.

## Type Converters

### Built-in Converters

The extension handles common Java types automatically:
- `UUID`, `String`, `Integer`, `Long`, `Double`, `Float`, `Boolean`
- `Instant`, `LocalDate`, `LocalTime`
- `BigDecimal`, `BigInteger`
- `ByteBuffer`, `byte[]`
- Collections: `List`, `Set`, `Map`
- Enums via `@Enumerated(EnumType.STRING)` / `@Enumerated(EnumType.ORDINAL)`

**Not supported** — the driver has no codec for these, and a field of such a type
fails at runtime with `CodecNotFoundException`:

| Type | Use instead |
|------|-------------|
| `LocalDateTime` | `Instant` (a `LocalDateTime` has no time zone, so the conversion would be ambiguous), or an explicit `@Convert` |

### Custom Converters

Implement `AttributeConverter<EntityType, CqlType>`. The converter needs a public
no-argument constructor.

```java
public class JsonConverter implements AttributeConverter<MyObject, String> {

    @Override
    public String toCqlColumn(MyObject attribute) {
        return objectMapper.writeValueAsString(attribute);
    }

    @Override
    public MyObject toEntityAttribute(String dbData) {
        return objectMapper.readValue(dbData, MyObject.class);
    }
}

// Usage
@Table("my_table")
public class MyEntity {
    @Convert(JsonConverter.class)
    private MyObject data;
}
```

## Repository Generation Modes

Control which repositories are generated:

```java
// Generate both (default)
@GenerateRepository
@GenerateRepository(RepositoryType.BOTH)

// Blocking only
@GenerateRepository(RepositoryType.BLOCKING)

// Reactive only
@GenerateRepository(RepositoryType.REACTIVE)
```

## Best Practices

### Use Prepared Statements

The ORM automatically caches prepared statements. All repository methods use prepared statements for optimal performance.

### Pagination for Large Datasets

Always use pagination when querying large datasets:

```java
// Good
Paged<Data> page = repository.findAllPaged(Pageable.ofSize(100), null);

// Avoid for large tables
List<Data> all = repository.findAll();
```

### Composite Keys

Design your data model with ScyllaDB's partition and clustering key model in mind:

```java
@Table("time_series")
public class TimeSeries {
    @PartitionKey
    private String metricName;  // Partition key for distribution

    @ClusteringKey(order = ClusteringOrder.DESC)
    private Instant timestamp;  // Clustering for time-ordered access

    private double value;
}
```

### Reactive for High Throughput

Use reactive repositories for high-throughput scenarios:

```java
public Multi<ProcessedData> processStream() {
    return repository.findAll()
        .onItem().transform(this::process)
        .onFailure().retry().atMost(3);
}
```

## Building from Source

```bash
git clone https://github.com/UnvirtualHH/quarkus-scylladb-orm.git
cd quarkus-scylladb-orm
mvn clean install
```

Run tests (requires Docker for Testcontainers):

```bash
mvn verify
```

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
