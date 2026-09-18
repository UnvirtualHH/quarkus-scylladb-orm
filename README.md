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

- **Java 25+** — the published artifacts are compiled with `--release 25` (class file
  version 69). On an older JDK they fail to load with `UnsupportedClassVersionError`.
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
| `@ClusteringKey` | Marks field as clustering key (use `ordinal` for composite keys) |
| `@Column("name")` | Maps field to column (optional, defaults to field name) |
| `@GeneratedValue` | Assigns a random UUID on write when the field is null |
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

    @ClusteringKey(ordinal = 0)
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

CQL only allows `ORDER BY` when the partition key is restricted by `=` or `IN`, because
rows are only ordered *within* a partition. So sorting works on a partition-scoped query,
not on a full scan — `findAll`/`findAllPaged` reject a non-null `Sortable` for that reason:

```java
Sortable sort = Sortable.desc("occurred_at");

Paged<Event> page = eventRepository.queryPaged(
        "SELECT tenant, device_id, occurred_at, payload FROM event "
                + "WHERE tenant = :tenant AND device_id = :deviceId",
        Map.of("tenant", tenant, "deviceId", deviceId),
        Pageable.ofSize(20),
        sort);
```

The sort column must be a clustering column of that table; `Sortable` validates the name
against `[A-Za-z_][A-Za-z0-9_]*` before it reaches the statement, since it is interpolated
rather than bound.

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

# Serial consistency for LWT (default: LOCAL_SERIAL, matching the LOCAL_QUORUM default
# above — SERIAL makes every LWT a cross-DC round trip)
# Options: SERIAL, LOCAL_SERIAL
quarkus.scylla.request.serial-consistency=LOCAL_SERIAL

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

An unrecognised `type` fails at startup rather than falling back to `none` — a typo here
used to disable the overload protection silently.

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
| `quarkus.scylla.request.serial-consistency` | Serial consistency for LWT | `LOCAL_SERIAL` |
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
- A `@Query` may use the same `:name` more than once (`WHERE seen >= :ts AND touched >= :ts`).
  It becomes **one** method parameter bound to every marker; before, the generated
  signature repeated the name and did not compile.
- A projection (`@Query(resultClass = ...)`) can now read `List`/`Set`/`Map` columns.
  Before, such a field aborted generation with `not a valid name: List<java`.
- A projection into a plain class (not a record) now generates. It used to abort with
  `statement enter $[ followed by statement enter $[` — only record projections worked.
- Projection fields and record components honour `@Column`, so a `full_name` column can
  map to a `fullName` component without an alias in the CQL.
- Reactive `Multi` reads honour backpressure. They previously pushed every row of every
  page into an unbounded buffer as fast as the driver delivered them, so a slow consumer
  did not slow the fetching — it accumulated the whole result set in memory. Pages are
  now fetched on demand. No source change needed; a consumer that relied on the stream
  running ahead of it will now see the fetching pace itself.
- A lone `Map` argument is read as named parameters only when its keys are actual
  parameter names of the statement. `execute("UPDATE t SET attrs = ? WHERE ...", someMap)`
  now binds the map as a value; before it was always taken for named parameters and
  failed. `query(cql, Map.of("name", "John"))` is unchanged.

Newly rejected input (each was previously accepted and did the wrong thing silently):

| Input | Old behaviour | Now |
|-------|---------------|-----|
| `quarkus.scylla.throttler.type` with an unknown value (e.g. a typo) | Fell through to no throttling — the overload protection you configured was simply absent | Startup fails naming the value and the three valid ones |
| `Pageable` with size < 1 | The driver reads page size 0 as "no paging", so a paged read fetched the whole table in one page | `IllegalArgumentException` at construction |
| An entity with no `@PartitionKey` | Generated fine, then threw `ArrayIndexOutOfBoundsException` out of `exists()` at the first call | Build error against the entity |
| Two fields mapping to the same column (including a field shadowing an inherited one) | The generated `INSERT` listed the column twice and Scylla rejected it, naming neither field | Build error naming both fields |
| Two `@PartitionKey`/`@ClusteringKey` fields sharing an `ordinal` | The key order was left to field order, so `findByKeys` could bind arguments to the wrong columns | Build error naming the ordinal |
| A key field also annotated `@Transient`, or a field that is both `@PartitionKey` and `@ClusteringKey` | The mapper and the WHERE clause disagreed about the key | Build error |
| A raw, wildcard or nested-generic collection field (`List`, `List<? extends X>`, `List<List<String>>`) | Crashed the annotation processor ("threw an uncaught exception") or emitted `List<String>.class`, which does not compile | Build error naming the field |
| An `@Convert` converter whose CQL type cannot be resolved | Fell back to `row.get(col, Object.class)` and failed at runtime with `CodecNotFoundException` | Build error. A converter inheriting `AttributeConverter` from a base class now resolves correctly instead of hitting this at all. |
| A structural `:offset` in `@Query` | Interpolated into a clause the server rejects — CQL has no `OFFSET` | Build error pointing at `queryPaged` |
| `findAll(Pageable, Sortable)` / `findAllPaged(Pageable, Sortable)` with a non-null `Sortable` | Built `SELECT ... ORDER BY ...` with no `WHERE`, which Scylla always rejects — the argument could never work | `IllegalArgumentException` explaining that `ORDER BY` needs a restricted partition key |

Source-incompatible changes:

| Change | Migration |
|--------|-----------|
| Repositories are typed on their partition key (`Repository<Person, UUID>` instead of `Repository<Person, Object>`) | Fix any call passing a wrongly typed id — it was silently failing at runtime before. Entities with a composite partition key keep `Object`; use `findByKeys`. |
| `Paged` no longer has `totalElements` | It was hard-coded to `-1` on every path. Use `count()` if you really need a total. |
| `query`/`querySingle`/`queryScalar`/`execute`/`queryProjection` lost their 1/2/3-argument overloads | The varargs overload covers them; no call site should need changing. |
| `EntityMapper` gained `getColumnNames()` | Only affects hand-written mappers; generated ones are regenerated. |
| `KeyComponent` lost its `GenericType` (`of(name, type, value, ordinal)` → `of(name, value, ordinal)`, `type()` removed) | Nothing read it; it cost two allocations per key column on every keyed call and was the only `...type.reflect` type in generated code. Only affects hand-written mappers — drop the `GenericType.of(...)` argument. |
| `GeneratedValue.Strategy.SEQUENCE` removed | It was never implemented and silently did nothing. Use `UUID` or assign the value yourself. |
| `quarkus.scylla.request.serial-consistency` now defaults to `LOCAL_SERIAL` (was `SERIAL`) | Matches the DC-local `consistency` default, so an LWT no longer pays a cross-DC round trip while every other statement stays local. Set `SERIAL` explicitly if you need LWTs to linearize across datacenters. |
| Contact points are no longer resolved at startup | They are passed to the driver unresolved, so a DNS name is resolved again on every connection attempt instead of being pinned for the life of the session. Nothing to change; behind a Kubernetes service name this is the difference between reconnecting and not. |
| The extension is now three artifacts: `-api`, `-processor` and the runtime | Nothing to change — `quarkus-scylladb-orm` still pulls both in. See *Module layout* below. |

#### Overriding how a `@Query` parameter is bound

Parameters named `limit`, `order`, `orderby` or `sort` are interpolated into the CQL
rather than bound, because CQL has no bind marker for a column name in `ORDER BY`. That
heuristic used to be absolute, so an entity with a column actually called `sort` could not
query it. `@Query.Param` now takes a `binding`:

```java
@Query(name = "bySort",
       cql = "SELECT id, sort FROM sample WHERE sort = :sort",
       returnType = ReturnType.LIST,
       paramTypes = @Query.Param(name = "sort", type = String.class,
                                 binding = Query.Binding.BOUND))
```

`Binding.AUTO` (the default) keeps the name heuristic, `BOUND` always binds as a value,
`STRUCTURAL` always interpolates — after the same format check.

### Module layout

| Artifact | Contains | On the runtime classpath? |
|----------|----------|---------------------------|
| `quarkus-scylladb-orm-api` | `@Table`, `@Column`, … and `EntityMapper` | yes |
| `quarkus-scylladb-orm-processor` | the annotation processor and JavaPoet | yes, for now |
| `quarkus-scylladb-orm` | repositories, config, session producer | yes |
| `quarkus-scylladb-orm-deployment` | Quarkus build steps | no (build time only) |

Depend on `quarkus-scylladb-orm` as before; it pulls in the other two.

The processor only ever runs inside `javac`, so it has no business in a deployed
application — but Maven has no "annotation processing only" scope, and javac discovers
processors off the compile classpath. Keeping it at compile scope is what lets adding one
dependency generate your mappers with no further build configuration. Nothing at runtime
references it any more, so it and JavaPoet are unreachable for a native image's analysis;
removing them from JVM mode as well means switching that dependency to `provided` and asking
applications to declare the processor in `annotationProcessorPaths` themselves. That is a
breaking change, held for a release that can carry the migration note.

### Select lists and the entity mapper
The generated mapper reads **every** mapped field of the entity, so any query that maps
rows back to the entity has to select all of its columns.

For `@Query` this is handled at build time:

- `SELECT *` is expanded to the explicit column list. That avoids the driver's wildcard
  select warning (prepared-statement invalidation on CQL4, plus result metadata on every
  execution) at no cost to you.
- An explicit list that misses entity columns is a **compile error** naming the missing
  columns — it used to compile and then fail at runtime.
- Projections (`resultClass = MyDto.class`) are left alone; they map only the DTO's own
  fields, so a partial select is exactly right there.

  A projection reads each column by the DTO field's (or record component's) Java name,
  unless it carries its own `@Column` — the entity's `@Column` mappings do not apply,
  because the DTO is a different class:

  ```java
  public record PersonSummary(@Column("full_name") String fullName, int age) {}

  @Query(name = "summaries",
         cql = "SELECT full_name, age FROM person",
         returnType = ReturnType.LIST,
         resultClass = PersonSummary.class)
  ```

  Aliasing in the CQL (`SELECT full_name AS fullName ...`) works as well.
- Select lists using functions or aliases (`writetime(x)`, `x AS y`) are left alone —
  guessing there would turn working queries into build failures.

**Runtime CQL is not covered.** `repository.query(cql, ...)` and friends receive the
string at runtime, so nothing can check or rewrite it. There, selecting a subset still
fails with `IllegalArgumentException: <column> is not a column in this row`, and
`SELECT *` still triggers the driver warning. Prefer `@Query`, or spell the columns out.

### Blocking repositories refuse to run on the event loop
Every method of the generated blocking repository blocks. Called from a Vert.x event loop
thread it now fails fast with `BlockingOperationNotAllowedException` instead of stalling
the loop — inject the reactive repository, or annotate the caller with
`@io.smallrye.common.annotation.Blocking` so Quarkus dispatches it to a worker thread.

### Avoid unbounded scans on hot paths
`findAll()` (no paging) and `count()` perform cluster-wide scans that will time out and
overload coordinators on large tables. Use `findAll(Pageable, null)`, partition-scoped
`@Query` methods, or a maintained counter table instead.

### Reactive streams honour backpressure
`Multi`-returning reads (`findAll()`, `query(...)`, generated `@Query` methods with
`ReturnType.LIST`, `queryProjectionList`) fetch **one page at a time, on demand**. Asking
for *n* rows pulls at most the pages those rows live on; nothing is fetched before it is
requested, and cancelling stops the fetching.

That makes a bounded-memory consumer actually bounded:

```java
repository.query("SELECT ... FROM event WHERE tenant = ?", tenant)
    .onItem().transformToUniAndConcatenate(this::slowCall)   // demand of 1
    .collect().asList();
```

Pages are pulled only as fast as `slowCall` retires them, instead of the whole result set
piling up in memory.

Rows are emitted on the driver's I/O thread. If your consumer blocks or does heavy work,
add `.emitOn(...)` so it does not hold up that thread:

```java
repository.findAll()
    .emitOn(Infrastructure.getDefaultWorkerPool())
    .onItem().transform(this::expensive);
```

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

Pages are fetched on demand, so this streams in bounded memory however large the table is.
If `process` blocks or is expensive, add `.emitOn(...)` — items arrive on a driver I/O
thread. See [Reactive streams honour backpressure](#reactive-streams-honour-backpressure).

## Building from Source

```bash
git clone https://github.com/UnvirtualHH/quarkus-scylladb-orm.git
cd quarkus-scylladb-orm
mvn clean install
```

### Tests

`mvn verify` runs the unit tests plus the integration tests against a ScyllaDB started
via Testcontainers. Two groups are excluded from it because they are slow and would say
nothing on most commits:

```bash
# TLS end to end (starts a second, TLS-enabled ScyllaDB)
mvn verify -Dexcluded.test.groups=throughput -Dtest=TlsConnectionTest -Dsurefire.failIfNoSpecifiedTests=false

# Coarse write-path throughput against a real ScyllaDB
mvn verify -Dexcluded.test.groups=tls -Dtest=WriteThroughputTest -Dsurefire.failIfNoSpecifiedTests=false
```

CI runs both in their own steps.

### Benchmarks

JMH microbenchmarks for the generated mappers and the streaming pipeline live in
[`benchmarks/`](benchmarks/README.md). They are compiled by every build and run
explicitly:

```bash
mvn -pl benchmarks -am package -DskipTests
java -jar benchmarks/target/benchmarks.jar
```

Run tests (requires Docker for Testcontainers):

```bash
mvn verify
```

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
