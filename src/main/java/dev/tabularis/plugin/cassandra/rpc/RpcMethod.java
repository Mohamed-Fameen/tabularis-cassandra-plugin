package dev.tabularis.plugin.cassandra.rpc;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One handler for a single JSON-RPC method name. {@code params} is the raw
 * {@code "params"} object of the request (never null - callers get an empty
 * object node if the request omitted it). Implementations return the JSON
 * value that becomes the response's {@code "result"}, or throw
 * {@link RpcException} to produce a JSON-RPC error response.
 */
@FunctionalInterface
public interface RpcMethod {
    JsonNode handle(JsonNode params) throws RpcException;
}
