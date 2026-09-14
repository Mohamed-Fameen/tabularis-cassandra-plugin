#!/usr/bin/env bash
# Exercises every JSON-RPC method the plugin implements against a real
# Cassandra/ScyllaDB cluster, by piping newline-delimited JSON-RPC requests
# into the built jar (or, with --binary, a native-image binary) exactly as
# Tabularis itself would.
#
# Used two ways:
#   ./scripts/exercise-plugin.sh                 -> functional smoke test
#   ./scripts/exercise-plugin.sh --agent          -> runs under the GraalVM
#       native-image tracing agent and writes fresh reflect/resource config
#       into src/main/resources/META-INF/native-image/ (see that directory's
#       README.md). This is what .github/workflows/refresh-native-image-config.yml
#       automates against a Cassandra service container.
#
# Requires: a reachable Cassandra/Scylla at $CASSANDRA_HOST:$CASSANDRA_PORT
# (defaults localhost:9042) and cqlsh on PATH to seed a scratch keyspace.
set -euo pipefail

CASSANDRA_HOST="${CASSANDRA_HOST:-127.0.0.1}"
CASSANDRA_PORT="${CASSANDRA_PORT:-9042}"
KEYSPACE="${KEYSPACE:-tabularis_smoke}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_DIR="$ROOT_DIR/src/main/resources/META-INF/native-image/dev.tabularis.plugin.cassandra/tabularis-cassandra-plugin"

MODE="jar"
AGENT=false
for arg in "$@"; do
  case "$arg" in
    --agent) AGENT=true ;;
    --binary) MODE="binary" ;;
    *) echo "Unknown argument: $arg" >&2; exit 1 ;;
  esac
done

echo "==> Seeding scratch keyspace \"$KEYSPACE\" on $CASSANDRA_HOST:$CASSANDRA_PORT"
cqlsh "$CASSANDRA_HOST" "$CASSANDRA_PORT" -e \
  "CREATE KEYSPACE IF NOT EXISTS $KEYSPACE WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};"
cqlsh "$CASSANDRA_HOST" "$CASSANDRA_PORT" -e \
  "CREATE TABLE IF NOT EXISTS $KEYSPACE.widgets (id uuid PRIMARY KEY, name text, weight double, tags set<text>, created_at timestamp) WITH comment = 'smoke-test table';"
cqlsh "$CASSANDRA_HOST" "$CASSANDRA_PORT" -e \
  "CREATE INDEX IF NOT EXISTS ON $KEYSPACE.widgets (name);"
cqlsh "$CASSANDRA_HOST" "$CASSANDRA_PORT" -e \
  "INSERT INTO $KEYSPACE.widgets (id, name, weight, tags, created_at) VALUES (uuid(), 'seed-row', 1.5, {'a','b'}, toTimestamp(now()));"

REQUESTS_FILE="$(mktemp)"
trap 'rm -f "$REQUESTS_FILE"' EXIT

# Fixed id (rather than a freshly generated uuid) so insert/update/delete
# below all target the same, known row.
ROW_ID="11111111-1111-1111-1111-111111111111"

cat > "$REQUESTS_FILE" <<JSONRPC
{"jsonrpc":"2.0","method":"initialize","params":{"settings":{"local_datacenter":"datacenter1"}},"id":1}
{"jsonrpc":"2.0","method":"test_connection","params":{"params":{"host":"$CASSANDRA_HOST","port":$CASSANDRA_PORT,"database":"$KEYSPACE"}},"id":2}
{"jsonrpc":"2.0","method":"get_databases","params":{"params":{"host":"$CASSANDRA_HOST","port":$CASSANDRA_PORT,"database":"$KEYSPACE"}},"id":3}
{"jsonrpc":"2.0","method":"get_tables","params":{"params":{"host":"$CASSANDRA_HOST","port":$CASSANDRA_PORT,"database":"$KEYSPACE"},"schema":null},"id":4}
{"jsonrpc":"2.0","method":"get_columns","params":{"params":{"host":"$CASSANDRA_HOST","port":$CASSANDRA_PORT,"database":"$KEYSPACE"},"schema":null,"table":"widgets"},"id":5}
{"jsonrpc":"2.0","method":"get_indexes","params":{"params":{"host":"$CASSANDRA_HOST","port":$CASSANDRA_PORT,"database":"$KEYSPACE"},"schema":null,"table":"widgets"},"id":6}
{"jsonrpc":"2.0","method":"insert_record","params":{"params":{"host":"$CASSANDRA_HOST","port":$CASSANDRA_PORT,"database":"$KEYSPACE"},"schema":null,"table":"widgets","data":{"id":"$ROW_ID","name":"agent-row","weight":2.5,"created_at":"2026-01-01T00:00:00Z"}},"id":7}
{"jsonrpc":"2.0","method":"execute_query","params":{"params":{"host":"$CASSANDRA_HOST","port":$CASSANDRA_PORT,"database":"$KEYSPACE"},"query":"SELECT * FROM widgets","page":1,"page_size":1},"id":8}
{"jsonrpc":"2.0","method":"execute_query","params":{"params":{"host":"$CASSANDRA_HOST","port":$CASSANDRA_PORT,"database":"$KEYSPACE"},"query":"SELECT * FROM widgets","page":2,"page_size":1},"id":9}
{"jsonrpc":"2.0","method":"update_record","params":{"params":{"host":"$CASSANDRA_HOST","port":$CASSANDRA_PORT,"database":"$KEYSPACE"},"schema":null,"table":"widgets","pk_col":"id","pk_val":"$ROW_ID","col_name":"weight","new_val":3.5},"id":10}
{"jsonrpc":"2.0","method":"delete_record","params":{"params":{"host":"$CASSANDRA_HOST","port":$CASSANDRA_PORT,"database":"$KEYSPACE"},"schema":null,"table":"widgets","pk_col":"id","pk_val":"$ROW_ID"},"id":11}
{"jsonrpc":"2.0","method":"ping","params":{"params":{"host":"$CASSANDRA_HOST","port":$CASSANDRA_PORT,"database":"$KEYSPACE"}},"id":12}
JSONRPC

echo "==> Running the plugin against $(wc -l < "$REQUESTS_FILE") requests (mode=$MODE, agent=$AGENT)"

if [ "$MODE" = "binary" ]; then
  BIN="$ROOT_DIR/build/native/nativeCompile/tabularis-cassandra-plugin"
  [ -x "$BIN" ] || { echo "Native binary not found - run 'gradle nativeCompile' first" >&2; exit 1; }
  "$BIN" < "$REQUESTS_FILE"
else
  JAR="$(ls "$ROOT_DIR"/build/libs/*.jar | head -n1)"
  [ -n "$JAR" ] || { echo "Jar not found - run 'gradle jar' first" >&2; exit 1; }
  if [ "$AGENT" = true ]; then
    mkdir -p "$CONFIG_DIR"
    java "-agentlib:native-image-agent=config-output-dir=$CONFIG_DIR" -jar "$JAR" < "$REQUESTS_FILE"
    echo "==> Fresh reflect/resource config written to $CONFIG_DIR"
  else
    java -jar "$JAR" < "$REQUESTS_FILE"
  fi
fi
