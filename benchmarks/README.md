# Benchmarks

JMH microbenchmarks for the layer this project actually writes — the generated entity
mappers and the reactive streaming pipeline. No database, no network, no CDI, so the
numbers are stable enough to compare across commits on one machine.

They are **compiled by every ordinary build** (the module is in the reactor) so they
cannot rot, and **never run** as part of `mvn verify`. That build also assembles the
runnable uber-jar — about two seconds and a 41 MB `target/benchmarks.jar` — which is why
the file appears without you asking for it. The module is neither installed nor deployed,
and a release build skips it entirely.

## Running them

```bash
mvn -pl benchmarks -am package -DskipTests
java -jar benchmarks/target/benchmarks.jar
```

A single benchmark, or a quick smoke run:

```bash
java -jar benchmarks/target/benchmarks.jar MapperBenchmark.mapRowToEntity
java -jar benchmarks/target/benchmarks.jar -f 1 -wi 2 -i 3
```

Machine-readable output, for comparing two commits:

```bash
java -jar benchmarks/target/benchmarks.jar -rf json -rff before.json
```

CI runs them weekly and on demand (`.github/workflows/benchmarks.yml`) and keeps the
JSON as an artifact.

## What is measured

| Benchmark | What it covers |
|---|---|
| `MapperBenchmark.mapRowToEntity` | The generated `map(Row)` — runs once per row of every read |
| `MapperBenchmark.entityToProperties` | The generated `toProperties(entity)` — once per write |
| `MapperBenchmark.keyComponents` | Building the bind parameters for a keyed read or delete |
| `MapperBenchmark.keyMetadata` | The constant per-entity arrays the repositories ask for on every call |
| `StreamingBenchmark.streamAllRows` | The demand-driven paging pipeline, per row, at two page sizes |

`BenchEvent` is deliberately wide — composite key, temporal types, a converter, both
`@Enumerated` modes, all three collection kinds — because a narrow entity would only
benchmark the easy case.

## What is *not* measured

- **Driver decoding.** `FakeRow` hands back ready-made objects. Real rows spend
  significant time in the driver's codecs; that cost belongs to the driver.
- **Anything involving a network or a real ScyllaDB.** For a coarse end-to-end rate see
  `WriteThroughputTest` in `integration-tests` (tagged `throughput`).

So treat these as *the ORM's own per-row overhead*, not as a prediction of application
throughput.

## Orientation

Numbers from one developer machine (Apple Silicon, JDK 25), to give a sense of scale
rather than a target:

| Benchmark | ~ |
|---|---|
| `mapRowToEntity` | 68 ns/row |
| `entityToProperties` | 56 ns/write |
| `keyComponents` | 20 ns |
| `keyMetadata` | 6 ns |
| `streamAllRows` | ~5 ns/row |

The point of the last line: the streaming pipeline is a small fraction of the mapping
cost, which is itself a small fraction of a real read once the driver and the network
are counted.
