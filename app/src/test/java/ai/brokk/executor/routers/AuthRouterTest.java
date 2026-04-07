package ai.brokk.executor.routers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.BrokkAuthValidation;
import ai.brokk.ContextManager;
import ai.brokk.LlmOutputMeta;
import ai.brokk.cli.HeadlessConsole;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.project.MainProject;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessageType;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthRouterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContextManager contextManager;
    private CapturingConsole console;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        var project = new MainProject(tempDir);
        contextManager = new ContextManager(project);
        console = new CapturingConsole();
        contextManager.setIo(console);
    }

    @Test
    void handleGetValidate_returnsValidationPayloadAndForwardsToConsole() throws Exception {
        var router = new AuthRouter(
                contextManager,
                () -> "brk+11111111-1111-1111-1111-111111111111+abc",
                unused -> new BrokkAuthValidation(
                        BrokkAuthValidation.State.PAID_USER,
                        true,
                        true,
                        true,
                        12.34f,
                        "Valid Brokk API key for a paid account."));

        var exchange = TestHttpExchange.request("GET", "/v1/auth/validate");
        router.handle(exchange);

        assertEquals(200, exchange.responseCode());
        Map<String, Object> body = MAPPER.readValue(exchange.responseBodyBytes(), new TypeReference<>() {});
        assertEquals("PAID_USER", body.get("state"));
        assertEquals(true, body.get("valid"));
        assertEquals(true, body.get("subscribed"));
        assertEquals(true, body.get("hasBalance"));
        assertEquals("Valid Brokk API key for a paid account.", body.get("message"));
        assertNotNull(body.get("balance"));

        var emitted = console.lastValidation.get();
        assertNotNull(emitted);
        assertEquals(BrokkAuthValidation.State.PAID_USER, emitted.state());
        assertTrue(emitted.hasBalance());
    }

    @Test
    void handlePostValidate_returnsMethodNotAllowed() throws Exception {
        var router = new AuthRouter(
                contextManager,
                () -> "brk+11111111-1111-1111-1111-111111111111+abc",
                unused -> new BrokkAuthValidation(
                        BrokkAuthValidation.State.FREE_USER,
                        true,
                        false,
                        true,
                        0.25f,
                        "Valid Brokk API key for a free account."));

        var exchange = TestHttpExchange.request("POST", "/v1/auth/validate");
        router.handle(exchange);

        assertEquals(405, exchange.responseCode());
        var payload = MAPPER.readValue(exchange.responseBodyBytes(), ErrorPayload.class);
        assertEquals(ErrorPayload.Code.METHOD_NOT_ALLOWED, payload.code());
    }

    @Test
    void handleUnknownPath_returnsNotFound() throws Exception {
        var router = new AuthRouter(
                contextManager,
                () -> "brk+11111111-1111-1111-1111-111111111111+abc",
                unused -> new BrokkAuthValidation(
                        BrokkAuthValidation.State.FREE_USER,
                        true,
                        false,
                        true,
                        0.25f,
                        "Valid Brokk API key for a free account."));

        var exchange = TestHttpExchange.request("GET", "/v1/auth/unknown");
        router.handle(exchange);

        assertEquals(404, exchange.responseCode());
        var payload = MAPPER.readValue(exchange.responseBodyBytes(), ErrorPayload.class);
        assertEquals(ErrorPayload.Code.NOT_FOUND, payload.code());
    }

    private static final class CapturingConsole extends HeadlessConsole {
        private final AtomicReference<BrokkAuthValidation> lastValidation = new AtomicReference<>();

        @Override
        protected void printLlmOutput(String token, ChatMessageType type, LlmOutputMeta meta) {
            // Suppress test output noise.
        }

        @Override
        public void brokkAuthValidationUpdated(BrokkAuthValidation validation) {
            lastValidation.set(validation);
        }
    }
}
