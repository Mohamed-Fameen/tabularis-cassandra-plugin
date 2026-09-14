package dev.tabularis.plugin.cassandra.driver;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.IndexMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.tabularis.plugin.cassandra.model.ConnectionParams;
import dev.tabularis.plugin.cassandra.rpc.RpcException;
import dev.tabularis.plugin.cassandra.util.CqlTypeMapper;

import java.util.List;
import java.util.Optional;

/**
 * Implements the schema-discovery JSON-RPC methods against CQL cluster
 * metadata: {@code get_databases} (keyspaces), {@code get_tables}, {@code
 * get_columns}, {@code get_indexes}.
 *
 * <p>Cassandra has no separate "schema" level between database and table -
 * a keyspace *is* the database (manifest {@code capabilities.schemas:
 * false}). Every method here therefore resolves its target keyspace from
 * {@link ConnectionParams#keyspace()} first, falling back to an explicit
 * {@code schema} request parameter if the host ever sends one.
 */
public class SchemaService {

    private final SessionRegistry sessions;
    private final ObjectMapper mapper;

    public SchemaService(SessionRegistry sessions, ObjectMapper mapper) {
        this.sessions = sessions;
        this.mapper = mapper;
    }

    public ArrayNode getDatabases(ConnectionParams params) throws RpcException {
        CqlSession session = sessions.getOrOpen(params);
        ArrayNode result = mapper.createArrayNode();
        Metadata metadata = session.getMetadata();
        for (CqlIdentifier ks : metadata.getKeyspaces().keySet()) {
            result.add(ks.asInternal());
        }
        return result;
    }

    public ArrayNode getTables(ConnectionParams params, String schemaOverride) throws RpcException {
        KeyspaceMetadata keyspace = resolveKeyspace(params, schemaOverride);
        ArrayNode result = mapper.createArrayNode();
        for (TableMetadata table : keyspace.getTables().values()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("name", table.getName().asInternal());
            node.put("schema", keyspace.getName().asInternal());
            node.put("comment", tableComment(table).orElse(null));
            result.add(node);
        }
        return result;
    }

    public ArrayNode getColumns(ConnectionParams params, String schemaOverride, String tableName) throws RpcException {
        TableMetadata table = resolveTable(params, schemaOverride, tableName);
        List<CqlIdentifier> primaryKeyIds = table.getPrimaryKey().stream().map(ColumnMetadata::getName).toList();

        ArrayNode result = mapper.createArrayNode();
        for (ColumnMetadata column : table.getColumns().values()) {
            boolean isPk = primaryKeyIds.contains(column.getName());
            ObjectNode node = mapper.createObjectNode();
            node.put("name", column.getName().asInternal());
            node.put("data_type", CqlTypeMapper.cqlName(column.getType()));
            // Primary key components are the only columns CQL guarantees are
            // non-null; everything else is nullable (CQL has no NOT NULL
            // constraint on regular columns).
            node.put("is_nullable", !isPk);
            node.put("default_value", (String) null);
            node.put("is_pk", isPk);
            // CQL has no auto-increment; monotonic ids are conventionally
            // client-generated UUIDs/timeuuids, not a server-side sequence.
            node.put("is_auto_increment", false);
            node.put("comment", (String) null);
            result.add(node);
        }
        return result;
    }

    public ArrayNode getIndexes(ConnectionParams params, String schemaOverride, String tableName) throws RpcException {
        TableMetadata table = resolveTable(params, schemaOverride, tableName);
        ArrayNode result = mapper.createArrayNode();
        for (IndexMetadata index : table.getIndexes().values()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("index_name", index.getName().asInternal());
            ArrayNode columns = mapper.createArrayNode();
            // CQL index targets are expressions (a plain column, or things
            // like keys(col)/full(col)/values(col) for collections) rather
            // than a plain column list; we surface the raw target verbatim
            // since it's the only faithful representation.
            columns.add(index.getTarget());
            node.set("columns", columns);
            node.put("is_unique", false);
            node.put("is_primary", false);
            result.add(node);
        }
        return result;
    }

    /** Package-visible for {@link MutationService} and {@link QueryService}. */
    TableMetadata resolveTable(ConnectionParams params, String schemaOverride, String tableName) throws RpcException {
        KeyspaceMetadata keyspace = resolveKeyspace(params, schemaOverride);
        return keyspace.getTable(tableName)
                .orElseThrow(() -> RpcException.invalidParams(
                        "Table \"" + tableName + "\" not found in keyspace \"" + keyspace.getName().asInternal() + "\""));
    }

    private KeyspaceMetadata resolveKeyspace(ConnectionParams params, String schemaOverride) throws RpcException {
        CqlSession session = sessions.getOrOpen(params);
        String keyspaceName = schemaOverride != null && !schemaOverride.isBlank() ? schemaOverride : params.keyspace();
        if (keyspaceName == null || keyspaceName.isBlank()) {
            throw RpcException.invalidParams("No keyspace selected - choose a keyspace (\"database\") for this connection");
        }
        return session.getMetadata().getKeyspace(keyspaceName)
                .orElseThrow(() -> RpcException.invalidParams("Keyspace \"" + keyspaceName + "\" not found"));
    }

    private static Optional<String> tableComment(TableMetadata table) {
        Object comment = table.getOptions().get(CqlIdentifier.fromCql("comment"));
        if (comment == null) {
            return Optional.empty();
        }
        String text = String.valueOf(comment);
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }
}
