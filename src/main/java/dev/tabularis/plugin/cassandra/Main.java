package dev.tabularis.plugin.cassandra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.tabularis.plugin.cassandra.driver.MutationService;
import dev.tabularis.plugin.cassandra.driver.PluginSettings;
import dev.tabularis.plugin.cassandra.driver.QueryService;
import dev.tabularis.plugin.cassandra.driver.SchemaService;
import dev.tabularis.plugin.cassandra.driver.SessionRegistry;
import dev.tabularis.plugin.cassandra.model.ConnectionParams;
import dev.tabularis.plugin.cassandra.rpc.Dispatcher;
import dev.tabularis.plugin.cassandra.rpc.RpcException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Process entry point. Implements the transport described in
 * plugins/PLUGIN_GUIDE.md: read newline-delimited JSON-RPC 2.0 requests from
 * stdin, write newline-terminated JSON-RPC responses to stdout, and never let
 * an exception escape to stderr in a way that would kill the process (the
 * host reuses this one process for every operation in a connection session).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        ObjectMapper mapper = new ObjectMapper();

        SessionRegistry sessions = new SessionRegistry();
        SchemaService schemaService = new SchemaService(sessions, mapper);
        QueryService queryService = new QueryService(sessions, mapper);
        MutationService mutationService = new MutationService(sessions, schemaService);

        Dispatcher dispatcher = new Dispatcher(mapper);
        registerMethods(dispatcher, mapper, sessions, schemaService, queryService, mutationService);

        Runtime.getRuntime().addShutdownHook(new Thread(sessions::close, "session-cleanup"));

        PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                ObjectNode response;
                try {
                    JsonNode request = mapper.readTree(line);
                    response = dispatcher.dispatch(request);
                } catch (Exception parseFailure) {
                    response = dispatcher.parseErrorResponse("Invalid JSON: " + parseFailure.getMessage());
                }
                out.print(mapper.writeValueAsString(response));
                out.print('\n');
                out.flush();
            }
        } finally {
            sessions.close();
        }
    }

    private static void registerMethods(Dispatcher dispatcher, ObjectMapper mapper, SessionRegistry sessions,
                                         SchemaService schemaService, QueryService queryService, MutationService mutationService) {

        dispatcher.register("initialize", params -> {
            sessions.applySettings(PluginSettings.fromJson(params.get("settings")));
            return mapper.nullNode();
        });

        dispatcher.register("test_connection", params -> {
            ConnectionParams cp = connectionParams(params);
            boolean ok = sessions.probe(cp);
            ObjectNode result = mapper.createObjectNode();
            result.put("success", ok);
            return result;
        });

        dispatcher.register("ping", params -> {
            sessions.probe(connectionParams(params));
            return mapper.nullNode();
        });

        dispatcher.register("get_databases", params -> schemaService.getDatabases(connectionParams(params)));

        // Cassandra/Scylla have no schema level distinct from the keyspace
        // (manifest capabilities.schemas: false), so there is nothing to list.
        dispatcher.register("get_schemas", params -> mapper.createArrayNode());

        // CQL has no foreign-key constraints.
        dispatcher.register("get_foreign_keys", params -> mapper.createArrayNode());

        dispatcher.register("get_tables", params ->
                schemaService.getTables(connectionParams(params), textOrNull(params, "schema")));

        dispatcher.register("get_columns", params ->
                schemaService.getColumns(connectionParams(params), textOrNull(params, "schema"), requireText(params, "table")));

        dispatcher.register("get_indexes", params ->
                schemaService.getIndexes(connectionParams(params), textOrNull(params, "schema"), requireText(params, "table")));

        dispatcher.register("execute_query", params -> queryService.executeQuery(
                connectionParams(params),
                requireText(params, "query"),
                params.path("page").asInt(1),
                params.path("page_size").asInt(100)));

        dispatcher.register("insert_record", params -> {
            mutationService.insertRecord(
                    connectionParams(params),
                    textOrNull(params, "schema"),
                    requireText(params, "table"),
                    params.get("data"));
            return mapper.nullNode();
        });

        dispatcher.register("update_record", params -> {
            int affected = mutationService.updateRecord(
                    connectionParams(params),
                    textOrNull(params, "schema"),
                    requireText(params, "table"),
                    requireText(params, "pk_col"),
                    params.get("pk_val"),
                    requireText(params, "col_name"),
                    params.get("new_val"));
            return mapper.getNodeFactory().numberNode(affected);
        });

        dispatcher.register("delete_record", params -> {
            int affected = mutationService.deleteRecord(
                    connectionParams(params),
                    textOrNull(params, "schema"),
                    requireText(params, "table"),
                    requireText(params, "pk_col"),
                    params.get("pk_val"));
            return mapper.getNodeFactory().numberNode(affected);
        });
    }

    private static ConnectionParams connectionParams(JsonNode params) throws RpcException {
        try {
            return ConnectionParams.fromJson(params.get("params"));
        } catch (IllegalArgumentException e) {
            throw RpcException.invalidParams(e.getMessage());
        }
    }

    private static String requireText(JsonNode params, String field) throws RpcException {
        String value = textOrNull(params, field);
        if (value == null || value.isBlank()) {
            throw RpcException.invalidParams("\"" + field + "\" is required");
        }
        return value;
    }

    private static String textOrNull(JsonNode params, String field) {
        JsonNode value = params.get(field);
        return (value == null || value.isNull()) ? null : value.asText(null);
    }
}
