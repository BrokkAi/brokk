package ai.brokk.acpserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.IConsoleIO;
import ai.brokk.LlmOutputMeta;
import ai.brokk.acpserver.agent.SyncPromptContext;
import ai.brokk.acpserver.transport.AcpTransport;
import dev.langchain4j.data.message.ChatMessageType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrokkAcpServerTest {

    @Test
    void acpProgressConsoleSendsMessageChunks() {
        var transport = new MockAcpTransport();
        var ctx = new SyncPromptContext("test-session", transport);

        ctx.sendMessage("Hello, world!");

        assertEquals(1, transport.getSentNotifications().size());
        var notification = transport.getSentNotifications().get(0);
        assertEquals("session/update", notification.method());
    }

    @Test
    void acpProgressConsoleSendsThoughts() {
        var transport = new MockAcpTransport();
        var ctx = new SyncPromptContext("test-session", transport);

        ctx.sendThought("Processing request...");

        assertEquals(1, transport.getSentNotifications().size());
        var notification = transport.getSentNotifications().get(0);
        assertEquals("session/update", notification.method());
    }

    @Test
    void acpProgressConsoleStreamsLlmOutput() {
        var transport = new MockAcpTransport();
        var ctx = new SyncPromptContext("test-session", transport);
        var delegate = new NoOpConsole();
        var console = new AcpProgressConsole(ctx, delegate);

        console.llmOutput("Hello ", ChatMessageType.AI, LlmOutputMeta.newMessage());
        console.llmOutput("world!", ChatMessageType.AI, LlmOutputMeta.DEFAULT);

        // Flush the token buffer
        console.shutdown();

        // Tokens may be batched together, so we expect at least 1 notification
        assertTrue(transport.getSentNotifications().size() >= 1);
        for (var notification : transport.getSentNotifications()) {
            assertEquals("session/update", notification.method());
        }

        // Verify messages are stored in transcript
        var messages = console.getLlmRawMessages();
        assertEquals(1, messages.size());
    }

    @Test
    void acpProgressConsoleStreamsBackgroundOutput() {
        var transport = new MockAcpTransport();
        var ctx = new SyncPromptContext("test-session", transport);
        var delegate = new NoOpConsole();
        var console = new AcpProgressConsole(ctx, delegate);

        console.backgroundOutput("Running analysis...");

        assertEquals(1, transport.getSentNotifications().size());
        assertEquals("session/update", transport.getSentNotifications().get(0).method());
    }

    @Test
    void acpProgressConsoleStreamsNotifications() {
        var transport = new MockAcpTransport();
        var ctx = new SyncPromptContext("test-session", transport);
        var delegate = new NoOpConsole();
        var console = new AcpProgressConsole(ctx, delegate);

        console.showNotification(IConsoleIO.NotificationRole.INFO, "Task completed");

        assertEquals(1, transport.getSentNotifications().size());
        assertEquals("session/update", transport.getSentNotifications().get(0).method());
    }

    @Test
    void acpProgressConsoleHandlesToolErrors() {
        var transport = new MockAcpTransport();
        var ctx = new SyncPromptContext("test-session", transport);
        var delegate = new NoOpConsole();
        var console = new AcpProgressConsole(ctx, delegate);

        console.toolError("File not found", "Read Error");

        assertEquals(1, transport.getSentNotifications().size());
        assertEquals("session/update", transport.getSentNotifications().get(0).method());
    }

    // Mock transport for testing
    private static class MockAcpTransport implements AcpTransport {
        private final List<SentNotification> sentNotifications = new ArrayList<>();

        @Override
        public void start(MessageHandler handler) {
            // No-op for tests
        }

        @Override
        public void sendResponse(Object id, Object result) {
            // No-op for tests
        }

        @Override
        public void sendErrorResponse(Object id, int code, String message) {
            // No-op for tests
        }

        @Override
        public void sendNotification(String method, Object params) {
            sentNotifications.add(new SentNotification(method, params));
        }

        @Override
        public void close() {
            // No-op for tests
        }

        public List<SentNotification> getSentNotifications() {
            return sentNotifications;
        }

        record SentNotification(String method, Object params) {}
    }

    // Minimal IConsoleIO implementation for testing
    private static class NoOpConsole implements IConsoleIO {
        @Override
        public void toolError(String msg, String title) {
            // No-op
        }

        @Override
        public void llmOutput(String token, ChatMessageType type, LlmOutputMeta meta) {
            // No-op
        }
    }
}
