package ai.brokk.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.executor.jobs.JobRunner;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.TestService;
import ai.brokk.util.GlobalUiSettings;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/**
 * Routing tests for {@link BrokkAcpRuntime}. Drives JSON-RPC requests through a fake transport so
 * each {@code AcpSchema.METHOD_*} / {@code AcpProtocol.METHOD_*} string the SDK or our protocol
 * additions emit is verified to land on a registered handler. A regression here is silent: a
 * forgotten {@code handlers.put(...)} returns {@code -32601 Method not found} and clients see the
 * agent as broken with no log line on our side that explains it.
 *
 * <p>Also covers {@link BrokkAcpRuntime#isOlderThan(String, String)} edge cases — the comparator
 * gates the IntelliJ compatibility warning, and an off-by-one would either spam every user or
 * miss the IDE versions we know are broken.
 */
class BrokkAcpRuntimeTest {

    private ContextManager contextManager;
    private JobRunner jobRunner;
    private BrokkAcpAgent agent;
    private FakeRuntimeTransport transport;
    private BrokkAcpRuntime runtime;
    private Path projectRoot;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        projectRoot = tempDir.toAbsolutePath().normalize();
        System.setProperty(
                "brokk.acp.settings.path",
                tempDir.resolve(".brokk-runtime-acp-settings.json")
                        .toAbsolutePath()
                        .toString());
        var project = MainProject.forTests(projectRoot);
        var testService = new TestService(project);
        contextManager = new ContextManager(project, new ai.brokk.Service.Provider() {
            @Override
            public ai.brokk.AbstractService get() {
                return testService;
            }

            @Override
            public void reinit(ai.brokk.project.IProject p) {
                // no-op for the routing test; we never trigger a project reinit
            }
        });
        var jobStore = new JobStore(projectRoot.resolve(".brokk-runtime-test-jobs"));
        jobRunner = new JobRunner(contextManager, jobStore);
        agent = new BrokkAcpAgent(contextManager, jobRunner, jobStore);
        transport = new FakeRuntimeTransport();
        runtime = new BrokkAcpRuntime(transport, agent);
    }

    @AfterEach
    void tearDown() {
        GlobalUiSettings.resetForTests();
        if (runtime != null) {
            runtime.close();
        }
        if (jobRunner != null) {
            jobRunner.shutdown();
        }
        if (contextManager != null) {
            contextManager.close();
        }
        System.clearProperty("brokk.acp.settings.path");
    }

    // ---- Version comparator ----

    @Test
    void isOlderThanRecognizesShorterEqualPrefix() {
        // A 2-component version is "older than" a 3-component version that shares its prefix.
        assertTrue(BrokkAcpRuntime.isOlderThan("2026.1", "2026.1.1"));
        assertFalse(BrokkAcpRuntime.isOlderThan("2026.1.1", "2026.1"));
        // Exact equality is not "older".
        assertFalse(BrokkAcpRuntime.isOlderThan("2026.1.1", "2026.1.1"));
    }

    @Test
    void isOlderThanIgnoresEAPSuffixOnComponents() {
        // IntelliJ ships EAP builds with suffixes; the comparator strips the non-digit tail per
        // component so 2026.1-EAP compares equal to 2026.1, not less-than.
        assertFalse(BrokkAcpRuntime.isOlderThan("2026.1-EAP", "2026.1"));
        assertFalse(BrokkAcpRuntime.isOlderThan("2026.1.1-EAP", "2026.1.1"));
        assertTrue(BrokkAcpRuntime.isOlderThan("2026.0.9-EAP", "2026.1.1"));
    }

    @Test
    void isOlderThanComparesDigitsNumericallyNotLexically() {
        // Lexically "10" < "9", but numerically 10 > 9. Use numeric comparison.
        assertTrue(BrokkAcpRuntime.isOlderThan("2026.9.0", "2026.10.0"));
        assertFalse(BrokkAcpRuntime.isOlderThan("2026.10.0", "2026.9.0"));
    }

    // ---- Handler dispatch ----

    @Test
    void initializeRequestRoutesToAgent() {
        var params = Map.of(
                "protocolVersion", AcpSchema.LATEST_PROTOCOL_VERSION,
                "clientCapabilities", Map.of(),
                "clientInfo", Map.of("name", "TestClient", "version", "1.0", "title", "Test"));
        var response = transport.exchange(AcpSchema.METHOD_INITIALIZE, "1", params);

        assertNull(response.error(), () -> "initialize must not error: " + response.error());
        assertNotNull(response.result());
    }

    @Test
    void initializeWithOldIntelliJSetsCompatibilityWarning() {
        var oldVersion = Map.of(
                "protocolVersion", AcpSchema.LATEST_PROTOCOL_VERSION,
                "clientCapabilities", Map.of(),
                "clientInfo", Map.of("name", "JetBrains.IntelliJ IDEA", "version", "2024.3", "title", "IntelliJ"));
        transport.exchange(AcpSchema.METHOD_INITIALIZE, "1", oldVersion);

        // The agent must hold a non-null warning so prompt() short-circuits before invoking the
        // LLM on an unsupported IDE. Asserting the surface directly catches a regression where
        // setCompatibilityWarning(...) is dropped — without this, both branches of the if/else
        // would pass the test silently.
        var warning = agent.compatibilityWarning();
        assertNotNull(warning, "old IntelliJ version must set the compatibility warning");
        assertTrue(warning.contains("2026.1.1"), "warning must mention the required version, got: " + warning);

        // Re-initializing with a current version must clear the warning so prompts flow again.
        var fresh = Map.of(
                "protocolVersion", AcpSchema.LATEST_PROTOCOL_VERSION,
                "clientCapabilities", Map.of(),
                "clientInfo", Map.of("name", "JetBrains.IntelliJ IDEA", "version", "2026.1.1", "title", "IntelliJ"));
        var second = transport.exchange(AcpSchema.METHOD_INITIALIZE, "2", fresh);
        assertNull(second.error());
        assertNull(agent.compatibilityWarning(), "current IntelliJ version must clear the warning");
    }

    @Test
    void newSessionRequestRoutesToAgent() {
        var resp = transport.exchange(
                AcpSchema.METHOD_SESSION_NEW, "n1", Map.of("cwd", projectRoot.toString(), "mcpServers", List.of()));
        assertNull(resp.error(), () -> "session/new must not error: " + resp.error());
        assertNotNull(resp.result());
    }

    @Test
    void unknownMethodReturnsMethodNotFoundError() {
        var resp = transport.exchange("brokk/totally-unregistered", "x", Map.of());
        assertNotNull(resp.error(), "unknown method should return JSON-RPC error");
        // -32601 is the JSON-RPC standard "method not found" code.
        assertEquals(-32601, resp.error().code());
    }

    @Test
    void allDocumentedAcpMethodsAreRouted() {
        // Drive every public AcpSchema.METHOD_* and AcpProtocol.METHOD_* the runtime claims to
        // handle. We don't care about the response contents — only that the runtime doesn't
        // bounce them with -32601. A forgotten handlers.put(METHOD_FOO, ...) is the regression
        // we're guarding against; a flat-bug here is invisible without this test.
        var sessionId = createSession();
        record Routed(String method, Map<String, Object> params) {}
        // Each method registered in BrokkAcpRuntime.requestHandlers() — drop one here when we
        // intentionally remove a handler. METHOD_INITIALIZE and METHOD_SESSION_NEW are already
        // exercised by their dedicated tests above; this list covers the rest.
        var cases = List.of(
                new Routed(AcpSchema.METHOD_AUTHENTICATE, Map.of("methodId", "default")),
                new Routed(
                        AcpSchema.METHOD_SESSION_LOAD,
                        Map.of("sessionId", sessionId, "cwd", projectRoot.toString(), "mcpServers", List.of())),
                new Routed(AcpProtocol.METHOD_SESSION_LIST, Map.of("cwd", projectRoot.toString())),
                new Routed(
                        AcpProtocol.METHOD_SESSION_RESUME,
                        Map.of("sessionId", sessionId, "cwd", projectRoot.toString())),
                new Routed(AcpProtocol.METHOD_SESSION_CLOSE, Map.of("sessionId", sessionId)),
                new Routed(
                        AcpProtocol.METHOD_SESSION_FORK, Map.of("sessionId", sessionId, "cwd", projectRoot.toString())),
                new Routed(AcpSchema.METHOD_SESSION_SET_MODE, Map.of("sessionId", sessionId, "modeId", "ASK")),
                new Routed(AcpSchema.METHOD_SESSION_SET_MODEL, Map.of("sessionId", sessionId, "modelId", "any-model")),
                new Routed(
                        AcpProtocol.METHOD_SESSION_SET_CONFIG_OPTION,
                        Map.of("sessionId", sessionId, "configId", "behavior_mode", "value", "ASK")),
                // session/prompt is dispatched onto the boundedElastic worker; we feed it a
                // request that won't reach the LLM (no compatibility warning is set, but the
                // empty prompt + missing capabilities will short-circuit). The point isn't the
                // outcome — only that the runtime registers the handler.
                new Routed(AcpSchema.METHOD_SESSION_PROMPT, Map.of("sessionId", sessionId, "prompt", List.of())));

        for (var c : cases) {
            var resp = transport.exchange(c.method(), c.method() + "-id", c.params());
            // Either the call succeeds (resp.error() == null) OR it fails with a domain error
            // — both are fine. The one outcome we're catching is METHOD_NOT_FOUND, which means
            // the runtime never registered the handler.
            if (resp.error() != null) {
                assertFalse(
                        resp.error().code() == -32601,
                        () -> "method " + c.method() + " is not routed (got -32601 Method not found)");
            }
        }
    }

    @Test
    void cancelNotificationIsAcceptedWithoutErrorResponse() {
        var sessionId = createSession();
        // Notifications don't get a response per JSON-RPC; the test verifies the handler is
        // wired by checking no exception propagates through the transport pipeline.
        transport.dispatchNotification(AcpSchema.METHOD_SESSION_CANCEL, Map.of("sessionId", sessionId));
        // If we got here without an exception, the notification handler was registered.
    }

    @Test
    void closeIsIdempotent() {
        runtime.close();
        runtime.close();
        // Second close must not throw or leak — agent.stop() and session.close() are both
        // idempotent contracts that the AtomicBoolean gate enforces.
    }

    private String createSession() {
        var resp = transport.exchange(
                AcpSchema.METHOD_SESSION_NEW,
                "create-session",
                Map.of("cwd", projectRoot.toString(), "mcpServers", List.of()));
        assertNull(resp.error(), () -> "session/new failed: " + resp.error());
        // The handler returns a typed NewSessionResponseExt record; convert via the same
        // mapper the SDK uses on the wire so we get a uniform map view regardless of whether
        // the result is a record, a map, or a Jackson tree.
        var asMap = McpJsonDefaults.getMapper().convertValue(resp.result(), new TypeRef<Map<String, Object>>() {});
        return (String) asMap.get("sessionId");
    }

    /**
     * Synchronous fake transport: the runtime registers a request-handler chain with
     * {@code start(...)}, and tests drive that chain via {@link #exchange} / {@link
     * #dispatchNotification}. Only the inbound (client → agent) direction is exercised — outbound
     * permission/file/terminal round trips are out of scope for the routing test.
     */
    private static final class FakeRuntimeTransport implements AcpAgentTransport {
        private final McpJsonMapper mapper = McpJsonDefaults.getMapper();
        private final List<AcpSchema.JSONRPCMessage> sentMessages = new ArrayList<>();

        @SuppressWarnings("unused")
        private final Map<String, Function<Object, Object>> requestStubs = new ConcurrentHashMap<>();

        private Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler =
                ignored -> Mono.empty();

        AcpSchema.JSONRPCResponse exchange(String method, Object id, Object params) {
            var request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, id, method, params);
            var response = handler.apply(Mono.just(request)).block();
            return assertInstanceOf(AcpSchema.JSONRPCResponse.class, response);
        }

        void dispatchNotification(String method, Object params) {
            var notif = new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION, method, params);
            // Notifications must not produce a response per JSON-RPC; the runtime returns
            // Mono.empty() for them. Subscribe so any error propagates synchronously.
            handler.apply(Mono.just(notif)).block();
        }

        @Override
        public Mono<Void> start(Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler) {
            this.handler = handler;
            return Mono.empty();
        }

        @Override
        public void setExceptionHandler(Consumer<Throwable> handler) {}

        @Override
        public Mono<Void> awaitTermination() {
            return Mono.empty();
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.empty();
        }

        @Override
        public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
            sentMessages.add(message);
            return Mono.empty();
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return mapper.convertValue(data, typeRef);
        }
    }
}
