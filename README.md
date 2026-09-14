# tabularis-cassandra-plugin

A [Tabularis](https://tabularis.dev) driver plugin for **Apache Cassandra**
and **ScyllaDB**, written in Java against the
[DataStax Java Driver](https://github.com/datastax/java-driver) and shipped
as a GraalVM native-image binary. Built against the
[Cassandra/ScyllaDB plugin bounty](https://tabularis.dev/plugins/bounties):
keyspaces, tables, paged CQL queries, and row editing.

## Status

v1 implements:

- **Connection**: `initialize`, `test_connection`, `ping`
- **Schema browsing**: `get_databases` (keyspaces), `get_tables`, `get_columns`, `get_indexes`
- **Querying**: `execute_query` with CQL-native forward paging (see below)
- **Row editing**: `insert_record`, `update_record`, `delete_record`

See "Known limitations" below for what's deliberately out of scope for v1.

## Why Cassandra and ScyllaDB share one plugin

ScyllaDB is wire-compatible with Cassandra's CQL native protocol, so a
DataStax-driver-based client talks to either without any protocol-level
branching - this mirrors the bounty's own recommendation to build the
Cassandra path first and validate ScyllaDB on top of it rather than
duplicating a second plugin.

**Cassandra vs ScyllaDB, concretely:**

- Connection settings, schema discovery, querying, and row editing all work
  identically against both.
- ScyllaDB additionally supports *shard-aware* routing (a smarter native
  transport port per shard) for lower tail latency under load. This plugin
  does not implement shard-aware routing yet - the `scylla_shard_aware`
  connection setting is currently informational only (recorded for
  diagnostics, not acted on). Both databases work correctly without it; it's
  purely a performance optimization for high-throughput ScyllaDB workloads.
  Tracked as follow-up work.

## Building

Requires JDK 21 and, for a native binary, [GraalVM](https://www.graalvm.org/)
21 with `native-image` installed (`gu install native-image` if you're on a
JDK-only GraalVM distribution).

```bash
./gradlew build              # compile + run unit tests, produces build/libs/*.jar
./gradlew nativeCompile       # produces build/native/nativeCompile/tabularis-cassandra-plugin
```

> **Heads up if you're building in a network-restricted environment:** this
> project's dependencies (the DataStax driver, Jackson, JUnit/Mockito) come
> from Maven Central, and the Gradle plugin from the Gradle Plugin Portal.
> Both need to be reachable. CI (`.github/workflows/ci.yml`) builds, tests,
> and native-image-compiles on every push with full internet access, so if
> your local network is locked down, push a branch and let CI verify it.

### Building a native image

`build.gradle.kts` uses the
[GraalVM Native Build Tools](https://graalvm.github.io/native-build-tools/)
Gradle plugin and enables its
[Reachability Metadata Repository](https://github.com/oracle/graalvm-reachability-metadata)
integration, which supplies community-maintained native-image config for
common libraries on the classpath (Netty, in particular - the driver's
transport layer).

What that repository doesn't cover is this driver's own pluggable-policy
classes (load balancing, reconnection, retry, etc.), which
`DefaultDriverContext` resolves by class name from `reference.conf` and
instantiates reflectively even with default settings. A hand-written
starting point for those lives in
`src/main/resources/META-INF/native-image/dev.tabularis.plugin.cassandra/tabularis-cassandra-plugin/`
(see the `README.md` next to those files for exactly how it was derived).

**Before you ship a release build**, regenerate that config properly against
a live cluster using the GraalVM tracing agent, which observes everything
the app *actually* touches at runtime instead of relying on guesswork:

```bash
./gradlew jar
./scripts/exercise-plugin.sh --agent   # exercises every RPC method against
                                        # a real Cassandra and rewrites the
                                        # config files above
git diff src/main/resources/META-INF/native-image/
./gradlew nativeCompile                # confirm it still compiles
./scripts/exercise-plugin.sh --binary  # confirm the binary actually works
```

`.github/workflows/refresh-native-image-config.yml` automates this exact
flow against a Cassandra service container (manually triggerable, and runs
weekly) and opens a PR with whatever changed - config drift from a driver or
Netty upgrade gets caught there instead of at release time.

## Installing locally for development

Copy (or symlink) the built binary and manifest into Tabularis's plugin
directory, then restart Tabularis or reload plugins from Settings:

```bash
# Linux
mkdir -p ~/.local/share/tabularis/plugins/cassandra
cp build/native/nativeCompile/tabularis-cassandra-plugin ~/.local/share/tabularis/plugins/cassandra/
cp .tabularium ~/.local/share/tabularis/plugins/cassandra/
```

(macOS/Windows: see Tabularis's own docs for the equivalent plugin data
directory.)

## Manual protocol testing

`scripts/exercise-plugin.sh` pipes a full sequence of JSON-RPC requests
(connect, browse schema, page a query, insert/update/delete a row) into the
built JAR or native binary against a real Cassandra/Scylla, exactly as
Tabularis itself would:

```bash
docker run --rm -d --name smoke-cassandra -p 9042:9042 cassandra:5.0
# wait for it to come up (cqlsh 127.0.0.1 9042 -e "DESCRIBE KEYSPACES"), then:
./gradlew jar
./scripts/exercise-plugin.sh
```

You can also drive a single request by hand:

```bash
echo '{"jsonrpc":"2.0","method":"test_connection","params":{"params":{"host":"127.0.0.1","port":9042,"database":"my_keyspace"}},"id":1}' \
  | java -jar build/libs/tabularis-cassandra-plugin-*.jar
```

## Query paging & `total_count`

CQL has no `OFFSET`/row-number-based paging - a page is only reachable via
the opaque `PagingState` token the server returns alongside the previous
page. `QueryService` (see its and `PagingCache`'s javadoc) caches that token
per `(connection, query, page size)` so a grid paging forward one page at a
time - by far the common UI pattern - resumes exactly where it left off. A
page requested "out of order" (the cache has never seen `page - 1`) falls
back to replaying from page 1, which is correct but costs `O(page)` requests;
in practice this only happens after a process restart or a UI jump straight
to an unvisited page.

Similarly, CQL has no cheap `COUNT(*)` for an arbitrary statement - it's a
full coordinator-side scan, which this plugin deliberately never issues just
to populate a total. `total_count` is therefore:

- **exact**, once the last page has been reached (fewer rows returned than
  `page_size`, or the server reports no further paging state), or
- **`-1`**, meaning "unknown, more rows likely exist", on every earlier page.

## Known limitations

- **Composite primary keys and row editing.** Tabularis's `update_record`/
  `delete_record` protocol identifies a row with a single `pk_col`/`pk_val`
  pair. CQL primary keys are frequently composite (partition key plus
  clustering columns), which that shape can't express. Tables with a
  composite primary key are therefore browsable and queryable, but not
  editable through the grid - edit them with CQL via the query editor
  instead. `insert_record` is unaffected, since it supplies every column
  explicitly.
- **Collections, tuples, and UDTs are read-only.** They display correctly
  (including nested/frozen types, rendered exactly as `DESCRIBE TABLE`
  would), but `insert_record`/`update_record` don't yet accept `list`/`set`/
  `map`/`tuple`/user-defined-type values - write those via CQL. Scalar types
  (text, numerics, uuid, timestamp, date, time, blob, inet, boolean) are
  fully read/write.
- **No shard-aware ScyllaDB routing** - see "Why Cassandra and ScyllaDB share
  one plugin" above.
- **Secondary index columns** are reported using CQL's raw index target
  expression (e.g. `keys(tags)`, `full(tags)`) rather than a parsed column
  list, since CQL index targets are expressions, not always plain columns.

## Configuration (`.tabularium` settings)

| Setting | Default | Notes |
|---|---|---|
| `local_datacenter` | `datacenter1` | Required by the driver's default load-balancing policy; must match a real datacenter name in your cluster. |
| `consistency_level` | `LOCAL_QUORUM` | Any standard CQL consistency level. |
| `request_timeout_ms` | `10000` | Applies to both connection and per-request timeouts. |
| `scylla_shard_aware` | `false` | Informational only for now - see "Known limitations". |

## Publishing a release

1. Bump `version` in `.tabularium` and `gradle.properties`.
2. Tag `vX.Y.Z` matching that version and push the tag - `.github/workflows/release.yml`
   builds native binaries for linux-x64, linux-arm64, darwin-x64,
   darwin-arm64, and win-x64, packages each with a platform-specific copy of
   `.tabularium`, and attaches them (plus the root `.tabularium`) to a GitHub
   Release.
3. Submit the release at [registry.tabularis.dev/submit](https://registry.tabularis.dev/submit)
   (or open a PR against `plugins/registry.json` in the main Tabularis repo,
   per `plugins/PLUGIN_GUIDE.md`'s "Publishing" section).

## License

MIT - see [LICENSE](LICENSE).
