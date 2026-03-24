package ai.brokk.acpserver.transport;

import ai.brokk.acpserver.agent.AcpProtocolException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Stdio-based transport for ACP agent communication.
 * <p>
 * Reads JSON-RPC 2.0 messages from stdin (one per line) and writes responses to stdout.
 * This matches the transport pattern used by MCP and the ACP SDK.
 */
public class StdioAcpAgentTransport implements AcpTransport {

    private static final Logger logger = LogManager.getLogger(StdioAcpAgentTransport.class);

    private final ObjectMapper mapper;
    private final InputStream input;
    private final BufferedReader reader;
    private final PrintWriter writer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    /**
     * Creates a transport using System.in and System.out.
     */
    public StdioAcpAgentTransport() {
        this(System.in, System.out);
    }

    /**
     * Creates a transport with custom streams (useful for testing).
     *
     * @param input the input stream to read from
     * @param output the output stream to write to
     */
    public StdioAcpAgentTransport(InputStream input, OutputStream output) {
        this.mapper = createObjectMapper();
        this.input = input;
        this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.writer = new PrintWriter(output, false, StandardCharsets.UTF_8);
    }

    private static ObjectMapper createObjectMapper() {
        return new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    @Override
    public void start(MessageHandler handler) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Transport already started");
        }

        logger.info("ACP Stdio transport starting");

        try {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                processMessage(line, handler);
            }
        } catch (IOException e) {
            if (running.get()) {
                logger.error("Error reading from stdin", e);
            }
        } finally {
            running.set(false);
            logger.info("ACP Stdio transport stopped");
        }
    }

    private void processMessage(String line, MessageHandler handler) {
        JsonRpcMessage.Request request;
        try {
            request = mapper.readValue(line, JsonRpcMessage.Request.class);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to parse JSON-RPC message: {}", e.getMessage());
            sendParseError();
            return;
        }

        if (request.jsonrpc() == null || !request.jsonrpc().equals(JsonRpcMessage.JSONRPC_VERSION)) {
            logger.warn("Invalid JSON-RPC version: {}", request.jsonrpc());
            if (request.id() != null) {
                sendErrorResponse(request.id(), JsonRpcMessage.RpcError.INVALID_REQUEST, "Invalid JSON-RPC version");
            }
            return;
        }

        if (request.method() == null || request.method().isBlank()) {
            logger.warn("Missing method in JSON-RPC request");
            if (request.id() != null) {
                sendErrorResponse(request.id(), JsonRpcMessage.RpcError.INVALID_REQUEST, "Missing method");
            }
            return;
        }

        logger.debug("Received request: method={}, id={}", request.method(), request.id());

        if (request.isNotification()) {
            try {
                handler.handle(request.method(), request.params(), null);
            } catch (Exception e) {
                logger.warn("Error handling notification {}: {}", request.method(), e.getMessage());
            }
            return;
        }

        // Run session/prompt on a worker thread so the main loop stays free for cancel
        if ("session/prompt".equals(request.method())) {
            var promptThread = new Thread(
                    () -> {
                        try {
                            dispatchRequest(request, handler);
                        } catch (Throwable t) {
                            logger.error("Prompt thread died unexpectedly", t);
                            sendErrorResponse(
                                    request.id(),
                                    JsonRpcMessage.RpcError.INTERNAL_ERROR,
                                    t.getClass().getName());
                        }
                    },
                    "BrokkACP-Prompt");
            promptThread.setDaemon(true);
            promptThread.start();
            return;
        }

        dispatchRequest(request, handler);
    }

    private void dispatchRequest(JsonRpcMessage.Request request, MessageHandler handler) {
        var method = request.method();
        if (method == null) {
            return;
        }
        try {
            var result = handler.handle(method, request.params(), request.id());
            if (result != null) {
                sendResponse(request.id(), result);
            }
        } catch (AcpProtocolException e) {
            logger.warn("Protocol error handling request {}: {}", method, e.getMessage());
            var msg = e.getMessage();
            sendErrorResponse(request.id(), e.code(), msg != null ? msg : "Protocol error");
        } catch (Exception e) {
            logger.error("Error handling request {}: {}", method, e.getMessage(), e);
            var msg = e.getMessage();
            sendErrorResponse(
                    request.id(),
                    JsonRpcMessage.RpcError.INTERNAL_ERROR,
                    msg != null ? msg : e.getClass().getName());
        }
    }

    private void sendParseError() {
        var response = JsonRpcMessage.Response.error(null, JsonRpcMessage.RpcError.PARSE_ERROR, "Parse error");
        writeMessage(response);
    }

    @Override
    public void sendResponse(@Nullable Object id, Object result) {
        var response = JsonRpcMessage.Response.success(id, result);
        writeMessage(response);
    }

    @Override
    public void sendErrorResponse(@Nullable Object id, int code, String message) {
        var response = JsonRpcMessage.Response.error(id, code, message);
        writeMessage(response);
    }

    @Override
    public void sendNotification(String method, Object params) {
        var notification = JsonRpcMessage.Notification.create(method, params);
        writeMessage(notification);
    }

    private void writeMessage(Object message) {
        try {
            String json = mapper.writeValueAsString(message);
            synchronized (writeLock) {
                writer.println(json);
                writer.flush();
            }
            logger.debug("Sent message: {}", json);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize message", e);
            String fallback =
                    "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Internal serialization error\"}}";
            synchronized (writeLock) {
                writer.println(fallback);
                writer.flush();
            }
        }
    }

    @Override
    public void close() {
        running.set(false);
        try {
            input.close();
        } catch (IOException e) {
            logger.debug("Error closing input stream", e);
        }
        synchronized (writeLock) {
            writer.flush();
        }
    }

    /**
     * Returns the ObjectMapper used by this transport.
     * <p>
     * Useful for tests that need to serialize/deserialize messages.
     */
    public ObjectMapper getMapper() {
        return mapper;
    }
}
