package ai.brokk.acpserver.transport;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import org.jetbrains.annotations.Nullable;

/**
 * JSON-RPC 2.0 message types for ACP transport.
 */
public final class JsonRpcMessage {

    public static final String JSONRPC_VERSION = "2.0";

    private JsonRpcMessage() {}

    /**
     * JSON-RPC 2.0 request message.
     * <p>
     * When {@code id} is null, this represents a notification (no response expected).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(
            @Nullable String jsonrpc, @Nullable String method, @Nullable JsonNode params, @Nullable Object id) {
        public boolean isNotification() {
            return id == null;
        }
    }

    /**
     * JSON-RPC 2.0 response message.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(String jsonrpc, @Nullable Object result, @Nullable RpcError error, @Nullable Object id) {
        public static Response success(@Nullable Object id, Object result) {
            return new Response(JSONRPC_VERSION, result, null, id);
        }

        public static Response error(@Nullable Object id, int code, String message) {
            return new Response(JSONRPC_VERSION, null, new RpcError(code, message, null), id);
        }

        public static Response error(@Nullable Object id, RpcError error) {
            return new Response(JSONRPC_VERSION, null, error, id);
        }
    }

    /**
     * JSON-RPC 2.0 notification message (request without id).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Notification(String jsonrpc, String method, Object params) {
        public static Notification create(String method, Object params) {
            return new Notification(JSONRPC_VERSION, method, params);
        }
    }

    /**
     * JSON-RPC 2.0 error object.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RpcError(int code, String message, @Nullable Object data) {
        // Standard JSON-RPC error codes
        public static final int PARSE_ERROR = -32700;
        public static final int INVALID_REQUEST = -32600;
        public static final int METHOD_NOT_FOUND = -32601;
        public static final int INVALID_PARAMS = -32602;
        public static final int INTERNAL_ERROR = -32603;
    }
}
