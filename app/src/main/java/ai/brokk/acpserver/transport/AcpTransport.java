package ai.brokk.acpserver.transport;

import com.fasterxml.jackson.databind.JsonNode;
import org.jetbrains.annotations.Nullable;

/**
 * Transport interface for ACP (Agent Client Protocol) communication.
 * <p>
 * Implementations handle the low-level JSON-RPC 2.0 message framing over
 * different transports (stdio, WebSocket, etc.).
 */
public interface AcpTransport {

    /**
     * Starts the transport and begins processing incoming messages.
     * <p>
     * This method may block (for stdio) or return immediately (for async transports).
     * Incoming requests are dispatched to the provided handler.
     *
     * @param handler the message handler for incoming requests
     */
    void start(MessageHandler handler);

    /**
     * Sends a JSON-RPC response for a request.
     *
     * @param id the request ID to respond to (may be null for notifications, though responses shouldn't be sent)
     * @param result the result object (will be serialized to JSON)
     */
    void sendResponse(@Nullable Object id, Object result);

    /**
     * Sends a JSON-RPC error response.
     *
     * @param id the request ID to respond to (may be null for notifications)
     * @param code the error code
     * @param message the error message
     */
    void sendErrorResponse(@Nullable Object id, int code, String message);

    /**
     * Sends a JSON-RPC notification (no response expected).
     *
     * @param method the notification method name
     * @param params the notification parameters
     */
    void sendNotification(String method, Object params);

    /**
     * Closes the transport and releases resources.
     */
    void close();

    /**
     * Handler for incoming JSON-RPC requests.
     */
    @FunctionalInterface
    interface MessageHandler {
        /**
         * Handles an incoming JSON-RPC request.
         *
         * @param method the method name
         * @param params the parameters (may be null)
         * @param id the request ID (null for notifications)
         * @return the result object, or null if this is a notification
         * @throws Exception if handling fails
         */
        Object handle(String method, @Nullable JsonNode params, @Nullable Object id) throws Exception;
    }
}
