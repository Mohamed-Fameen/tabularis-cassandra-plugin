package dev.tabularis.plugin.cassandra.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Routes JSON-RPC 2.0 requests to registered {@link RpcMethod} handlers and
 * builds well-formed JSON-RPC response envelopes. This class owns no I/O -
 * {@link dev.tabularis.plugin.cassandra.Main} is responsible for reading and
 * writing the newline-delimited stdio stream described in
 * plugins/PLUGIN_GUIDE.md.
 */
public class Dispatcher {

    private final ObjectMapper mapper;
    private final Map<String, RpcMethod> methods = new LinkedHashMap<>();

    public Dispatcher(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void register(String name, RpcMethod method) {
        methods.put(name, method);
    }

    /** Handles one already-parsed JSON-RPC request object, never throws. */
    public ObjectNode dispatch(JsonNode request) {
        JsonNode idNode = request.has("id") ? request.get("id") : null;

        String method = request.path("method").asText(null);
        if (method == null || method.isBlank()) {
            return errorResponse(idNode, RpcException.INVALID_REQUEST, "Missing \"method\"");
        }

        RpcMethod handler = methods.get(method);
        if (handler == null) {
            return errorResponse(idNode, RpcException.METHOD_NOT_FOUND, "Method not implemented: " + method);
        }

        JsonNode params = request.has("params") ? request.get("params") : mapper.createObjectNode();

        try {
            JsonNode result = handler.handle(params);
            return successResponse(idNode, result == null ? mapper.nullNode() : result);
        } catch (RpcException e) {
            return errorResponse(idNode, e.getCode(), e.getMessage());
        } catch (Exception e) {
            // Any unexpected runtime failure (driver exceptions, NPEs, etc.)
            // degrades to an internal error rather than killing the process -
            // a crashed plugin process would break the whole connection
            // session for the host, per PLUGIN_GUIDE.md's process lifecycle.
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return errorResponse(idNode, RpcException.INTERNAL_ERROR, message);
        }
    }

    public ObjectNode parseErrorResponse(String message) {
        return errorResponse(null, RpcException.PARSE_ERROR, message);
    }

    private ObjectNode successResponse(JsonNode id, JsonNode result) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("result", result);
        response.set("id", id == null ? mapper.nullNode() : id);
        return response;
    }

    private ObjectNode errorResponse(JsonNode id, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        ObjectNode error = mapper.createObjectNode();
        error.put("code", code);
        error.put("message", message == null ? "Unknown error" : message);
        response.set("error", error);
        response.set("id", id == null ? mapper.nullNode() : id);
        return response;
    }
}
