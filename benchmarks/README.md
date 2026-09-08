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

Allocation per operation, which matters more than nanoseconds for a service under
sustained load — it is what feeds GC pressure and therefore tail latency:

```bash
java -jar benchmarks/target/benchmarks.jar -prof gc
```

CI runs them weekly and on demand (`.github/workflows/benchmarks.yml`) and keeps the
JSON as an artifact.

## What is measured

| Benchmark | What it covers |
|---|---|
| `MapperBenchmark.generatedMap` | The generated `map(Row)` — runs once per row of every read |
| `MapperBenchmark.generatedToProperties` | The generated `toProperties(entity)` — once per write |
| `MapperBenchmark.handWrittenMap` | The identical mapping written by hand — the comparison that makes the two above mean something |
| `MapperBenchmark.handWrittenToProperties` | Likewise for the write side |
| `MapperBenchmark.rowAccessOnly` | The column reads alone — the harness floor, to be subtracted |
| `MapperBenchmark.keyComponents` | Building the bind parameters for a keyed read or delete |
| `MapperBenchmark.keyMetadata` | The constant per-entity arrays the repositories ask for on every call |
| `StreamingBenchmark.streamAllRows` | The demand-driven paging pipeline, per row, at two page sizes |

A benchmark without a baseline cannot be acted on: 60-something nanoseconds is neither
good nor bad on its own. The hand-written pair is what turns it into an answer.

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

One developer machine (Apple Silicon, JDK 25, `-f 2 -wi 5 -i 5`). Absolute numbers mean
nothing on another host; the *ratios* are the point.

| Benchmark | ns/op |
|---|---|
| `rowAccessOnly` | 10.9 |
| `generatedMap` | 63.9 |
| `handWrittenMap` | 69.4 |
| `generatedToProperties` | 57.4 |
| `handWrittenToProperties` | 57.5 |
| `keyComponents` | 19.7 |
| `keyMetadata` | 5.7 |
| `streamAllRows` (100 rows/page) | 0.50 ms / 100k rows ≈ 5 ns/row |
| `streamAllRows` (5000 rows/page) | 0.46 ms / 100k rows ≈ 4.6 ns/row |

Reading them:

- **The generated mappers cost what hand-written code costs.** `generatedMap` is a
  little under `handWrittenMap`, `generatedToProperties` is level with its counterpart —
  both within a few percent. There is no code-generation tax to pay down here, which is
  the whole point of doing the work at compile time.
- **The harness is not the measurement.** `rowAccessOnly` is 11 ns of the ~64, so about
  53 ns is actual mapping across ten columns — roughly 5 ns per column for the
  allocation, the setter, the enum lookup and the converter.
- **Streaming is a rounding error next to mapping.** ~5 ns/row against ~64 ns/row, and
  page size barely matters. The demand-driven pipeline did not buy its safety with
  throughput.
- **Allocation is identical too**, byte for byte (`-prof gc`): 176 B/op for either
  `map`, 592 B/op for either `toProperties`. So the generated code is not trading memory
  for speed. At the measured 39,000 writes/s that is ~23 MB/s of short-lived garbage —
  well inside what a modern collector absorbs without a visible pause.
- **`keyMetadata` allocates 104 B/op** because `getColumnNames`/`getPartitionKeyNames`/
  `getClusteringKeyNames` each hand out a `clone()`, and the keyed repository methods ask
  on every call. Caching those in `EntityStatements` would remove it — but see the next
  point before spending an afternoon on it.
- **None of it is the bottleneck.** Against the measured end-to-end write path
  (`WriteThroughputTest`: ~39,000 rows/s concurrent, i.e. ~26 µs wall clock per row), the
  57 ns of `toProperties` is about 0.2%. The rest is the driver, the network and
  ScyllaDB. Optimising these numbers further would not move an application.
