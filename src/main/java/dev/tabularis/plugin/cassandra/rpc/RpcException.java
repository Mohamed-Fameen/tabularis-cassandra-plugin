package dev.tabularis.plugin.cassandra.rpc;

/**
 * A JSON-RPC 2.0 application error. The {@code code} follows the standard
 * ranges defined in plugins/PLUGIN_GUIDE.md: -32700..-32600 are reserved for
 * transport-level problems (handled in {@link Dispatcher} itself), -32603 is
 * the generic "internal error" bucket driver code should throw for anything
 * database-side (connection failures, CQL errors, etc.), and -32601 is
 * reserved for "method not implemented".
 */
public class RpcException extends Exception {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private final int code;

    public RpcException(int code, String message) {
        super(message);
        this.code = code;
    }

    public RpcException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public static RpcException notFound(String method) {
        return new RpcException(METHOD_NOT_FOUND, "Method not implemented: " + method);
    }

    public static RpcException invalidParams(String message) {
        return new RpcException(INVALID_PARAMS, message);
    }

    public static RpcException internal(String message, Throwable cause) {
        return new RpcException(INTERNAL_ERROR, message, cause);
    }

    public static RpcException internal(String message) {
        return new RpcException(INTERNAL_ERROR, message);
    }

    public int getCode() {
        return code;
    }
}
