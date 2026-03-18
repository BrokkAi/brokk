package ai.brokk.acpserver.transport;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StdioAcpAgentTransportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testRequestResponseRoundTrip() throws Exception {
        // Prepare a JSON-RPC request
        String request =
                """
                {"jsonrpc":"2.0","method":"initialize","params":{"protocolVersion":1},"id":1}
                """;

        ByteArrayInputStream input = new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        StdioAcpAgentTransport transport = new StdioAcpAgentTransport(input, output);

        AtomicReference<String> receivedMethod = new AtomicReference<>();
        AtomicReference<JsonNode> receivedParams = new AtomicReference<>();

        transport.start((method, params, id) -> {
            receivedMethod.set(method);
            receivedParams.set(params);
            return new TestResponse("ok", 1);
        });

        // Verify the request was handled
        assertEquals("initialize", receivedMethod.get());
        assertNotNull(receivedParams.get());
        assertEquals(1, receivedParams.get().get("protocolVersion").asInt());

        // Verify the response was written
        String responseJson = output.toString(StandardCharsets.UTF_8).trim();
        JsonNode response = mapper.readTree(responseJson);

        assertEquals("2.0", response.get("jsonrpc").asText());
        assertEquals(1, response.get("id").asInt());
        assertNotNull(response.get("result"));
        assertEquals("ok", response.get("result").get("status").asText());
    }

    @Test
    void testNotificationNoResponse() throws Exception {
        // Notification has no id
        String notification =
                """
                {"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"abc"}}
                """;

        ByteArrayInputStream input = new ByteArrayInputStream(notification.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        StdioAcpAgentTransport transport = new StdioAcpAgentTransport(input, output);

        AtomicReference<String> receivedMethod = new AtomicReference<>();

        transport.start((method, params, id) -> {
            receivedMethod.set(method);
            assertNull(id, "Notification should have null id");
            return null;
        });

        assertEquals("session/update", receivedMethod.get());

        // No response should be written for notifications
        String responseJson = output.toString(StandardCharsets.UTF_8).trim();
        assertTrue(responseJson.isEmpty(), "No response expected for notification");
    }

    @Test
    void testSendNotification() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[0]);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        StdioAcpAgentTransport transport = new StdioAcpAgentTransport(input, output);

        transport.sendNotification("session/update", new SessionUpdate("session-123", "chunk"));

        String notificationJson = output.toString(StandardCharsets.UTF_8).trim();
        JsonNode notification = mapper.readTree(notificationJson);

        assertEquals("2.0", notification.get("jsonrpc").asText());
        assertEquals("session/update", notification.get("method").asText());
        assertEquals("session-123", notification.get("params").get("sessionId").asText());
        assertFalse(notification.has("id"), "Notification should not have id");
    }

    @Test
    void testErrorResponse() throws Exception {
        String request = """
                {"jsonrpc":"2.0","method":"unknownMethod","id":42}
                """;

        ByteArrayInputStream input = new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        StdioAcpAgentTransport transport = new StdioAcpAgentTransport(input, output);

        transport.start((method, params, id) -> {
            throw new UnsupportedOperationException("Method not found: " + method);
        });

        String responseJson = output.toString(StandardCharsets.UTF_8).trim();
        JsonNode response = mapper.readTree(responseJson);

        assertEquals("2.0", response.get("jsonrpc").asText());
        assertEquals(42, response.get("id").asInt());
        assertNotNull(response.get("error"));
        assertEquals(
                JsonRpcMessage.RpcError.INTERNAL_ERROR,
                response.get("error").get("code").asInt());
    }

    @Test
    void testInvalidJson() throws Exception {
        String invalidJson = "not valid json\n";

        ByteArrayInputStream input = new ByteArrayInputStream(invalidJson.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        StdioAcpAgentTransport transport = new StdioAcpAgentTransport(input, output);

        transport.start((method, params, id) -> {
            fail("Handler should not be called for invalid JSON");
            return null;
        });

        String responseJson = output.toString(StandardCharsets.UTF_8).trim();
        JsonNode response = mapper.readTree(responseJson);

        assertEquals(
                JsonRpcMessage.RpcError.PARSE_ERROR,
                response.get("error").get("code").asInt());
    }

    @Test
    void testMultipleRequests() throws Exception {
        String requests =
                """
                {"jsonrpc":"2.0","method":"method1","id":1}
                {"jsonrpc":"2.0","method":"method2","id":2}
                {"jsonrpc":"2.0","method":"method3","id":3}
                """;

        ByteArrayInputStream input = new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        StdioAcpAgentTransport transport = new StdioAcpAgentTransport(input, output);

        var methodCounter = new java.util.concurrent.atomic.AtomicInteger(0);

        transport.start((method, params, id) -> {
            methodCounter.incrementAndGet();
            return new TestResponse(method, ((Number) id).intValue());
        });

        assertEquals(3, methodCounter.get());

        String[] responses = output.toString(StandardCharsets.UTF_8).trim().split("\n");
        assertEquals(3, responses.length);

        for (int i = 0; i < 3; i++) {
            JsonNode response = mapper.readTree(responses[i]);
            assertEquals(i + 1, response.get("id").asInt());
            assertEquals(
                    "method" + (i + 1), response.get("result").get("status").asText());
        }
    }

    @Test
    void testConcurrentNotifications() throws Exception {
        PipedOutputStream pipedOut = new PipedOutputStream();
        PipedInputStream pipedIn = new PipedInputStream(pipedOut);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        StdioAcpAgentTransport transport = new StdioAcpAgentTransport(pipedIn, output);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(1);

        Thread readerThread = new Thread(() -> {
            startLatch.countDown();
            transport.start((method, params, id) -> new TestResponse("ok", 1));
            doneLatch.countDown();
        });
        readerThread.start();

        assertTrue(startLatch.await(1, TimeUnit.SECONDS));

        // Send notifications from multiple threads
        Thread[] notifierThreads = new Thread[3];
        for (int i = 0; i < notifierThreads.length; i++) {
            final int index = i;
            notifierThreads[i] = new Thread(() -> {
                transport.sendNotification("test/notify", new SessionUpdate("session-" + index, "data"));
            });
            notifierThreads[i].start();
        }

        for (Thread t : notifierThreads) {
            t.join(1000);
        }

        // Close the pipe to end the reader
        pipedOut.close();
        assertTrue(doneLatch.await(1, TimeUnit.SECONDS));

        // Verify notifications were written (order may vary due to concurrency)
        String outputStr = output.toString(StandardCharsets.UTF_8);
        assertEquals(3, outputStr.lines().filter(l -> l.contains("test/notify")).count());
    }

    // Test helper records
    record TestResponse(String status, int requestId) {}

    record SessionUpdate(String sessionId, String data) {}
}
