package dev.tabularis.plugin.cassandra.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DispatcherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private Dispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new Dispatcher(mapper);
    }

    @Test
    void routesToRegisteredMethodAndWrapsResult() {
        dispatcher.register("ping", params -> mapper.getNodeFactory().textNode("pong"));

        ObjectNode response = dispatcher.dispatch(request("ping", null, 1));

        assertThat(response.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(response.get("id").asInt()).isEqualTo(1);
        assertThat(response.get("result").asText()).isEqualTo("pong");
        assertThat(response.has("error")).isFalse();
    }

    @Test
    void unknownMethodReturnsMethodNotFound() {
        ObjectNode response = dispatcher.dispatch(request("does_not_exist", null, 5));

        assertThat(response.get("error").get("code").asInt()).isEqualTo(RpcException.METHOD_NOT_FOUND);
        assertThat(response.get("id").asInt()).isEqualTo(5);
    }

    @Test
    void missingMethodFieldIsInvalidRequest() {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 7);

        ObjectNode response = dispatcher.dispatch(request);

        assertThat(response.get("error").get("code").asInt()).isEqualTo(RpcException.INVALID_REQUEST);
    }

    @Test
    void rpcExceptionFromHandlerBecomesStructuredError() {
        dispatcher.register("boom", params -> {
            throw RpcException.invalidParams("bad params");
        });

        ObjectNode response = dispatcher.dispatch(request("boom", null, 2));

        assertThat(response.get("error").get("code").asInt()).isEqualTo(RpcException.INVALID_PARAMS);
        assertThat(response.get("error").get("message").asText()).isEqualTo("bad params");
    }

    @Test
    void unexpectedExceptionDegradesToInternalErrorInsteadOfPropagating() {
        dispatcher.register("crash", params -> {
            throw new NullPointerException("driver blew up");
        });

        ObjectNode response = dispatcher.dispatch(request("crash", null, 3));

        assertThat(response.get("error").get("code").asInt()).isEqualTo(RpcException.INTERNAL_ERROR);
        assertThat(response.get("error").get("message").asText()).isEqualTo("driver blew up");
    }

    @Test
    void missingParamsIsPassedAsEmptyObjectNotNull() {
        dispatcher.register("needs_params", params -> {
            assertThat(params).isNotNull();
            assertThat(params.isObject()).isTrue();
            return mapper.nullNode();
        });

        ObjectNode response = dispatcher.dispatch(request("needs_params", null, 4));

        assertThat(response.has("error")).isFalse();
    }

    @Test
    void parseErrorResponseHasNullId() {
        ObjectNode response = dispatcher.parseErrorResponse("Invalid JSON: unexpected token");

        assertThat(response.get("error").get("code").asInt()).isEqualTo(RpcException.PARSE_ERROR);
        assertThat(response.get("id").isNull()).isTrue();
    }

    private JsonNode request(String method, JsonNode params, int id) {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }
        request.put("id", id);
        return request;
    }
}
