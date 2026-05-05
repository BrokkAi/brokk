package ai.brokk.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.executor.jobs.JobRunner;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.TestService;
import ai.brokk.util.GlobalUiSettings;
import com.agentclientprotocol.sdk.spec.AcpAgentSession;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/**
 * Integration-style tests for {@link AcpRequestContext}: drives a real {@link AcpAgentSession}
 * over a fake transport so the full Reactor pipeline (request, timeout, cache, sticky-allow)
 * runs the way it does in production. Covers the regressions called out in #3438:
 *
 * <ul>
 *   <li>{@code session/request_permission} round-trip ↔ option mapping.</li>
 *   <li>{@code allow_always} / {@code reject_always} sticky-cache update on the agent.</li>
 *   <li>{@code shell} / {@code unknown} tool names are NEVER cached, regardless of the verdict.</li>
 *   <li>{@link PermissionMode#BYPASS_PERMISSIONS} short-circuits before any round trip.</li>
 *   <li>{@link PermissionMode#READ_ONLY} rejects EDIT/EXECUTE with the canonical user message.</li>
 *   <li>30-minute deny-on-timeout: a verdict that never returns surfaces a {@code PermissionCancelled}
 *       outcome AND emits the user-facing chat notification (regression target — verdicts were
 *       previously silently lost in real sessions).</li>
 *   <li>{@code fs/read_text_file}, {@code fs/write_text_file} round trips.</li>
 *   <li>{@code askChoice} option-id mapping and the {@code <2 options} guard.</li>
 * </ul>
 */
class AcpRequestContextTest {

    private ContextManager contextManager;
    private JobRunner jobRunner;
    private BrokkAcpAgent agent;
    private FakeOutboundTransport transport;
    private AcpAgentSession session;
    private Path projectRoot;
    private String sessionId;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        projectRoot = tempDir.toAbsolutePath().normalize();
        System.setProperty(
                "brokk.acp.settings.path",
                tempDir.resolve(".brokk-ctx-acp-settings.json").toAbsolutePath().toString());
        var project = MainProject.forTests(projectRoot);
        var testService = new TestService(project);
        contextManager = new ContextManager(project, new ai.brokk.Service.Provider() {
            @Override
            public ai.brokk.AbstractService get() {
                return testService;
            }

            @Override
            public void reinit(ai.brokk.project.IProject p) {}
        });
        var jobStore = new JobStore(projectRoot.resolve(".brokk-ctx-test-jobs"));
        jobRunner = new JobRunner(contextManager, jobStore);
        agent = new BrokkAcpAgent(contextManager, jobRunner, jobStore);
        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        sessionId = created.sessionId();

        transport = new FakeOutboundTransport();
        session = new AcpAgentSession(Duration.ofMinutes(35), transport, Map.of(), Map.of());
    }

    @AfterEach
    void tearDown() {
        GlobalUiSettings.resetForTests();
        if (session != null) {
            session.close();
        }
        if (jobRunner != null) {
            jobRunner.shutdown();
        }
        if (contextManager != null) {
            contextManager.close();
        }
        System.clearProperty("brokk.acp.settings.path");
    }

    // ---- File round trips ----

    @Test
    void readFileSendsFsReadAndUnwrapsContent() {
        transport.respondTo(AcpSchema.METHOD_FS_READ_TEXT_FILE, params -> Map.of("content", "hello world"));
        var ctx = newContext();

        var content = ctx.readFile("/abs/path/to/file.txt");

        assertEquals("hello world", content);
        var sent = transport.lastRequestParams(AcpSchema.METHOD_FS_READ_TEXT_FILE);
        assertEquals("/abs/path/to/file.txt", sent.get("path"));
        assertEquals(sessionId, sent.get("sessionId"));
    }

    @Test
    void writeFileSendsFsWriteWithSessionAndPath() {
        transport.respondTo(AcpSchema.METHOD_FS_WRITE_TEXT_FILE, params -> Map.of());
        var ctx = newContext();

        ctx.writeFile("/abs/out.txt", "payload\n");

        var sent = transport.lastRequestParams(AcpSchema.METHOD_FS_WRITE_TEXT_FILE);
        assertEquals(sessionId, sent.get("sessionId"));
        assertEquals("/abs/out.txt", sent.get("path"));
        assertEquals("payload\n", sent.get("content"));
    }

    @Test
    void tryReadFileSwallowsExceptions() {
        // The transport stub fails the request; tryReadFile must convert that into Optional.empty
        // rather than propagating — its contract is "return what you can, never throw".
        var ctx = newContext();
        var got = ctx.tryReadFile("/missing/path");
        assertTrue(got.isEmpty());
    }

    // ---- askPermission: cache / mode interactions ----

    @Test
    void askPermissionAllowOnceReturnsTrueWithoutCaching() {
        respondToPermission("allow_once");
        var ctx = newContext();

        assertTrue(ctx.askPermission("delete foo.txt", "deleteFile"));
        assertTrue(agent.stickyPermissionFor(sessionId, "deleteFile").isEmpty());
    }

    @Test
    void askPermissionAllowAlwaysCachesAllow() {
        respondToPermission("allow_always");
        var ctx = newContext();

        assertTrue(ctx.askPermission("edit foo.txt", "editFile"));
        assertEquals(
                BrokkAcpAgent.PermissionVerdict.ALLOW,
                agent.stickyPermissionFor(sessionId, "editFile").orElseThrow());
    }

    @Test
    void askPermissionRejectAlwaysCachesDeny() {
        respondToPermission("reject_always");
        var ctx = newContext();

        assertFalse(ctx.askPermission("delete foo.txt", "deleteFile"));
        assertEquals(
                BrokkAcpAgent.PermissionVerdict.DENY,
                agent.stickyPermissionFor(sessionId, "deleteFile").orElseThrow());
    }

    @Test
    void shellToolNameIsNeverCached() {
        // "shell" is the cache-poisoning name: caching one approval would blanket-allow every
        // future shell command in the session. Even allow_always must NOT update the sticky cache.
        respondToPermission("allow_always");
        var ctx = newContext();

        ctx.askPermission("rm -rf /tmp/foo", "shell");
        assertTrue(agent.stickyPermissionFor(sessionId, "shell").isEmpty(), "shell verdicts must not be cached");
    }

    @Test
    void unknownToolNameIsNeverCached() {
        // The "unknown" sentinel comes from AcpPromptContext.askPermission(action) for non-tool
        // confirm dialogs. Caching it would conflate unrelated prompts under one cache key.
        respondToPermission("allow_always");
        var ctx = newContext();

        ctx.askPermission("Apply patch?");
        assertTrue(agent.stickyPermissionFor(sessionId, "unknown").isEmpty());
    }

    @Test
    void cachedAllowSkipsRoundTrip() {
        // First: prime the cache with a real round trip.
        respondToPermission("allow_always");
        var ctx = newContext();
        ctx.askPermission("edit foo", "editFile");
        var firstCount = transport.requestCountFor(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION);

        // Reset the stub so a second round trip would fail loudly if it happened.
        transport.respondTo(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, params -> {
            throw new AssertionError("cache hit must not trigger another request");
        });

        // Second: cache hit returns ALLOW without contacting the client.
        assertTrue(ctx.askPermission("edit foo", "editFile"));
        assertEquals(firstCount, transport.requestCountFor(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION));
    }

    @Test
    void cachedDenyShortCircuitsToFalse() {
        respondToPermission("reject_always");
        var ctx = newContext();
        ctx.askPermission("edit foo", "editFile");

        transport.respondTo(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, params -> {
            throw new AssertionError("denied tool must not re-prompt");
        });
        assertFalse(ctx.askPermission("edit foo", "editFile"));
    }

    @Test
    void bypassPermissionsModeSkipsRoundTrip() {
        agent.setSessionConfigOption(
                new AcpProtocol.SetSessionConfigOptionRequest(sessionId, "permission_mode", "bypassPermissions", null));
        transport.respondTo(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, params -> {
            throw new AssertionError("bypass mode must not contact the client");
        });
        var ctx = newContext();

        assertTrue(ctx.askPermission("edit foo", "editFile"));
        assertEquals(0, transport.requestCountFor(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION));
    }

    @Test
    void readOnlyModeRejectsEditWithoutRoundTrip() {
        agent.setSessionConfigOption(
                new AcpProtocol.SetSessionConfigOptionRequest(sessionId, "permission_mode", "readOnly", null));
        transport.respondTo(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, params -> {
            throw new AssertionError("read-only mode must not prompt for edits");
        });
        var ctx = newContext();

        assertFalse(ctx.askPermission("edit foo", "editFile"));
        // Read-only mode also pushes a denial chat message so the user understands why nothing
        // happened. We surface it via the same session/update channel that real prompts use.
        var rejectionUpdates = transport.sentNotifications(AcpSchema.METHOD_SESSION_UPDATE);
        assertTrue(
                rejectionUpdates.stream().anyMatch(n -> stringValue(n).contains(PermissionGate.READ_ONLY_REJECTION)),
                "expected the read-only rejection message in a session/update notification");
    }

    @Test
    void readOnlyModeAllowsReadKindToolWithoutRoundTrip() {
        agent.setSessionConfigOption(
                new AcpProtocol.SetSessionConfigOptionRequest(sessionId, "permission_mode", "readOnly", null));
        transport.respondTo(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, params -> {
            throw new AssertionError("read-only mode must not prompt for read-kind tools");
        });
        var ctx = newContext();

        assertTrue(ctx.askPermission("read foo.java", "readFile"));
    }

    // ---- askPermissionDetailed: option list shape ----

    @Test
    void askPermissionDetailedSandboxBypassAddsExtraOptions() {
        respondToPermission("allow_no_sandbox_once");
        var ctx = newContext();

        var decision = ctx.askPermissionDetailed("rm -rf node_modules", "runShellCommand", "shell:rm", true, null);
        assertEquals(AcpPromptContext.PermissionDecision.ALLOW_NO_SANDBOX, decision);

        // Verify the options array carried the sandbox-bypass entries: clients render them in
        // order, and a missing entry is invisible to the user.
        var sent = transport.lastRequestParams(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION);
        @SuppressWarnings("unchecked")
        var options = (List<Map<String, Object>>) sent.get("options");
        var ids = options.stream().map(o -> (String) o.get("optionId")).toList();
        assertTrue(ids.contains("allow_no_sandbox_once"), "expected allow_no_sandbox_once in: " + ids);
        assertTrue(ids.contains("allow_no_sandbox_always"), "expected allow_no_sandbox_always in: " + ids);
    }

    @Test
    void askPermissionDetailedAllowNoSandboxAlwaysCachesNoSandboxVerdict() {
        respondToPermission("allow_no_sandbox_always");
        var ctx = newContext();

        var decision =
                ctx.askPermissionDetailed("docker run --privileged …", "runShellCommand", "shell:docker", true, null);
        assertEquals(AcpPromptContext.PermissionDecision.ALLOW_NO_SANDBOX, decision);
        assertEquals(
                BrokkAcpAgent.PermissionVerdict.ALLOW_NO_SANDBOX,
                agent.stickyPermissionFor(sessionId, "shell:docker").orElseThrow());
    }

    // ---- 30-min deny-on-timeout regression ----

    @Test
    void permissionTimeoutReturnsCancelledAndPostsChatNotice() {
        // Drop the timeout to 50ms for the test; production keeps 30 minutes. The transport stub
        // never delivers a response, so the .timeout() pipe must fire.
        transport.swallowRequestsTo(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION);
        var ctx = new AcpRequestContext(session, sessionId, null, agent, Duration.ofMillis(50));

        boolean approved = ctx.askPermission("edit foo", "editFile");
        assertFalse(approved, "timeout must be denied (PermissionCancelled outcome → false)");

        // The fix in #X added a chat-message side-effect so users know the verdict was lost.
        // Without this, the prompt silently resolves "no" and users assume the agent ignored them.
        var notes = transport.sentNotifications(AcpSchema.METHOD_SESSION_UPDATE);
        assertTrue(
                notes.stream().anyMatch(n -> stringValue(n).contains("Permission request timed out")),
                "expected a 'Permission request timed out' notification in: " + notes);
    }

    // ---- askChoice ----

    @Test
    void askChoiceRoundTripsSelectedOption() {
        // The implementation tags options with their array index as optionId; the client picks
        // index "1" and we expect the second option string back.
        transport.respondTo(
                AcpSchema.METHOD_SESSION_REQUEST_PERMISSION,
                params -> Map.of("outcome", Map.of("outcome", "selected", "optionId", "1")));
        var ctx = newContext();

        var picked = ctx.askChoice("Pick one", "first", "second", "third");
        assertEquals("second", picked);
    }

    @Test
    void askChoiceCancelledReturnsNull() {
        transport.respondTo(
                AcpSchema.METHOD_SESSION_REQUEST_PERMISSION,
                params -> Map.of("outcome", Map.of("outcome", "cancelled")));
        var ctx = newContext();

        assertNull(ctx.askChoice("Pick", "a", "b"));
    }

    @Test
    void askChoiceRejectsLessThanTwoOptions() {
        var ctx = newContext();
        assertThrows(IllegalArgumentException.class, () -> ctx.askChoice("only one"));
        assertThrows(IllegalArgumentException.class, () -> ctx.askChoice("only one", "single"));
    }

    // ---- helpers ----

    private AcpRequestContext newContext() {
        return new AcpRequestContext(session, sessionId, null, agent);
    }

    private void respondToPermission(String optionId) {
        transport.respondTo(
                AcpSchema.METHOD_SESSION_REQUEST_PERMISSION,
                params -> Map.of("outcome", Map.of("outcome", "selected", "optionId", optionId)));
    }

    /** Walks an arbitrary object graph for any string value (notification payloads use nested maps). */
    private static String stringValue(Object node) {
        if (node == null) return "";
        if (node instanceof CharSequence cs) return cs.toString();
        if (node instanceof Map<?, ?> m) {
            var sb = new StringBuilder();
            for (var v : m.values()) sb.append(stringValue(v)).append(' ');
            return sb.toString();
        }
        if (node instanceof List<?> list) {
            var sb = new StringBuilder();
            for (var v : list) sb.append(stringValue(v)).append(' ');
            return sb.toString();
        }
        return node.toString();
    }

    /**
     * Outbound-direction transport: the runtime would normally feed inbound messages, but we
     * only need to fake responses to agent → client requests (permission, fs, …). Stubs can be
     * configured per-method via {@link #respondTo}, and {@link #swallowRequestsTo} simulates a
     * client that never replies (used by the timeout test).
     */
    private static final class FakeOutboundTransport implements AcpAgentTransport {
        private final McpJsonMapper mapper = McpJsonDefaults.getMapper();
        private final List<AcpSchema.JSONRPCMessage> sentMessages = new ArrayList<>();
        private final Map<String, Function<Object, Object>> requestStubs = new ConcurrentHashMap<>();
        private final Map<String, Object> swallowedMethods = new ConcurrentHashMap<>();
        private final AtomicReference<Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>>>
                handler = new AtomicReference<>(ignored -> Mono.empty());

        void respondTo(String method, Function<Object, Object> stub) {
            requestStubs.put(method, stub);
            swallowedMethods.remove(method);
        }

        /** Drop requests to {@code method} silently — used to verify timeout behavior. */
        void swallowRequestsTo(String method) {
            swallowedMethods.put(method, Boolean.TRUE);
            requestStubs.remove(method);
        }

        Map<String, Object> lastRequestParams(String method) {
            // Returns a Jackson-decoded view of the most recent request params for the method.
            for (int i = sentMessages.size() - 1; i >= 0; i--) {
                if (sentMessages.get(i) instanceof AcpSchema.JSONRPCRequest req && method.equals(req.method())) {
                    @SuppressWarnings("unchecked")
                    var asMap = mapper.convertValue(req.params(), Map.class);
                    @SuppressWarnings("unchecked")
                    var typed = (Map<String, Object>) asMap;
                    return typed;
                }
            }
            throw new AssertionError("no request observed for method " + method);
        }

        long requestCountFor(String method) {
            return sentMessages.stream()
                    .filter(AcpSchema.JSONRPCRequest.class::isInstance)
                    .map(AcpSchema.JSONRPCRequest.class::cast)
                    .filter(r -> method.equals(r.method()))
                    .count();
        }

        List<Object> sentNotifications(String method) {
            return sentMessages.stream()
                    .filter(AcpSchema.JSONRPCNotification.class::isInstance)
                    .map(AcpSchema.JSONRPCNotification.class::cast)
                    .filter(n -> method.equals(n.method()))
                    .map(AcpSchema.JSONRPCNotification::params)
                    .toList();
        }

        @Override
        public Mono<Void> start(Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> h) {
            this.handler.set(h);
            return Mono.empty();
        }

        @Override
        public void setExceptionHandler(Consumer<Throwable> h) {}

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
            if (message instanceof AcpSchema.JSONRPCRequest req) {
                if (swallowedMethods.containsKey(req.method())) {
                    // Intentionally never deliver a response — the .timeout() pipe will fire.
                    return Mono.empty();
                }
                var stub = requestStubs.get(req.method());
                if (stub != null) {
                    Object result;
                    try {
                        result = stub.apply(req.params());
                    } catch (Throwable t) {
                        // Surface AssertionErrors etc. into the handler chain so the test fails loudly.
                        return Mono.error(t);
                    }
                    var response = new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, req.id(), result, null);
                    Thread.startVirtualThread(
                            () -> handler.get().apply(Mono.just(response)).subscribe());
                }
            }
            return Mono.empty();
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return mapper.convertValue(data, typeRef);
        }
    }
}
