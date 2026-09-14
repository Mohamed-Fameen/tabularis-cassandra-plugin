package dev.tabularis.plugin.cassandra.driver;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import dev.tabularis.plugin.cassandra.model.ConnectionParams;
import dev.tabularis.plugin.cassandra.rpc.RpcException;
import dev.tabularis.plugin.cassandra.util.JsonConverter;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implements {@code insert_record}, {@code update_record}, {@code
 * delete_record}.
 *
 * <p><b>Known protocol limitation (documented in README "Known
 * limitations"):</b> {@code update_record}/{@code delete_record} identify a
 * row with a single {@code pk_col}/{@code pk_val} pair (see
 * plugins/PLUGIN_GUIDE.md). CQL primary keys are frequently composite
 * (partition key + clustering columns), which that single-column shape
 * cannot express. Tables whose primary key has more than one column are
 * therefore read-only through the grid; edit them with {@code execute_query}
 * CQL statements instead. {@code insert_record} is unaffected since it
 * supplies every column explicitly.
 */
public class MutationService {

    private final SessionRegistry sessions;
    private final SchemaService schemas;

    public MutationService(SessionRegistry sessions, SchemaService schemas) {
        this.sessions = sessions;
        this.schemas = schemas;
    }

    public void insertRecord(ConnectionParams params, String schema, String table, JsonNode data) throws RpcException {
        if (data == null || !data.isObject() || data.isEmpty()) {
            throw RpcException.invalidParams("\"data\" must be a non-empty object of column -> value");
        }
        TableMetadata meta = schemas.resolveTable(params, schema, table);

        List<String> quotedColumns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = data.fields();
        for (Map.Entry<String, JsonNode> field : data.properties()) {
            ColumnMetadata column = column(meta, field.getKey());
            quotedColumns.add(column.getName().asCql(true));
            values.add(convert(field.getValue(), column));
        }

        String placeholders = String.join(",", quotedColumns.stream().map(c -> "?").toList());
        String cql = "INSERT INTO " + qualifiedTable(meta) + " (" + String.join(",", quotedColumns) + ") VALUES (" + placeholders + ")";
        execute(params, cql, values);
    }

    public int updateRecord(ConnectionParams params, String schema, String table, String pkCol, JsonNode pkVal, String colName, JsonNode newVal) throws RpcException {
        TableMetadata meta = schemas.resolveTable(params, schema, table);
        ColumnMetadata pkColumn = requireSingleColumnPrimaryKey(meta, pkCol, "update");
        ColumnMetadata targetColumn = column(meta, colName);

        if (isPrimaryKeyColumn(meta, targetColumn)) {
            throw RpcException.invalidParams(
                    "\"" + colName + "\" is part of the primary key and cannot be changed in place; delete and re-insert the row instead");
        }

        Object newValue = convert(newVal, targetColumn);
        Object pkValue = convert(pkVal, pkColumn);

        String cql = "UPDATE " + qualifiedTable(meta) + " SET " + targetColumn.getName().asCql(true) + " = ? WHERE "
                + pkColumn.getName().asCql(true) + " = ?";
        execute(params, cql, List.of(newValue, pkValue));
        // CQL UPDATE is an upsert and the native protocol reports no
        // affected-row count (outside of lightweight transactions), so a
        // successful execute means exactly the one targeted row was written.
        return 1;
    }

    public int deleteRecord(ConnectionParams params, String schema, String table, String pkCol, JsonNode pkVal) throws RpcException {
        TableMetadata meta = schemas.resolveTable(params, schema, table);
        ColumnMetadata pkColumn = requireSingleColumnPrimaryKey(meta, pkCol, "delete");
        Object pkValue = convert(pkVal, pkColumn);

        String cql = "DELETE FROM " + qualifiedTable(meta) + " WHERE " + pkColumn.getName().asCql(true) + " = ?";
        execute(params, cql, List.of(pkValue));
        return 1;
    }

    private ColumnMetadata requireSingleColumnPrimaryKey(TableMetadata meta, String pkCol, String operation) throws RpcException {
        List<ColumnMetadata> primaryKey = meta.getPrimaryKey();
        if (primaryKey.size() != 1) {
            throw RpcException.invalidParams("Table \"" + meta.getName().asInternal() + "\" has a composite primary key ("
                    + primaryKey.size() + " columns); row " + operation + " isn't supported for it yet - use CQL via the query editor instead");
        }
        ColumnMetadata pkColumn = primaryKey.get(0);
        if (!pkColumn.getName().asInternal().equals(pkCol)) {
            throw RpcException.invalidParams("\"" + pkCol + "\" is not the primary key of \"" + meta.getName().asInternal() + "\"");
        }
        return pkColumn;
    }

    private static boolean isPrimaryKeyColumn(TableMetadata meta, ColumnMetadata column) {
        return meta.getPrimaryKey().stream().anyMatch(pk -> pk.getName().equals(column.getName()));
    }

    private static ColumnMetadata column(TableMetadata meta, String name) throws RpcException {
        Optional<ColumnMetadata> column = meta.getColumn(name);
        if (column.isEmpty()) {
            throw RpcException.invalidParams("Column \"" + name + "\" not found on \"" + meta.getName().asInternal() + "\"");
        }
        return column.get();
    }

    private static Object convert(JsonNode value, ColumnMetadata column) throws RpcException {
        try {
            return JsonConverter.toCqlValue(value, column.getType());
        } catch (IllegalArgumentException e) {
            throw RpcException.invalidParams("Column \"" + column.getName().asInternal() + "\": " + e.getMessage());
        }
    }

    private static String qualifiedTable(TableMetadata meta) {
        return meta.getKeyspace().asCql(true) + "." + meta.getName().asCql(true);
    }

    private void execute(ConnectionParams params, String cql, List<Object> values) throws RpcException {
        CqlSession session = sessions.getOrOpen(params);
        try {
            session.execute(SimpleStatement.newInstance(cql, values.toArray()));
        } catch (Exception e) {
            throw RpcException.internal("CQL write failed: " + rootMessage(e), e);
        }
    }

    private static String rootMessage(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
