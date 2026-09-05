package dev.mcmcp.protocol;

/**
 * JSON-RPC 2.0 error codes. Standard codes plus MCP implementation-defined range.
 * See IMPLEMENTATION.md §10.
 */
public final class JsonRpcErrors {
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    public static final int HEADER_MISMATCH = -32020;
    public static final int RATE_LIMITED = -32000;
    public static final int UNSUPPORTED_PROTOCOL_VERSION = -32021;
    public static final int REQUEST_TIMEOUT = -32003;

    private JsonRpcErrors() {}
}
