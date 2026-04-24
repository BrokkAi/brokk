package ai.brokk.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.executor.jobs.JobRunner;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.TestService;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

class BrokkAcpAgentTest {
    private ContextManager contextManager;
    private JobRunner jobRunner;
    private BrokkAcpAgent agent;
    private Path projectRoot;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        projectRoot = tempDir.toAbsolutePath().normalize();
        var project = MainProject.forTests(projectRoot);
        contextManager = new ContextManager(project, TestService.provider(project));
        var jobStore = new JobStore(projectRoot.resolve(".brokk-test-jobs"));
        jobRunner = new JobRunner(contextManager, jobStore);
        agent = new BrokkAcpAgent(contextManager, jobRunner, jobStore);
    }

    @AfterEach
    void tearDown() {
        if (jobRunner != null) {
            jobRunner.shutdown();
        }
        if (contextManager != null) {
            contextManager.close();
        }
    }

    @Test
    void initializeAdvertisesSessionCapabilities() {
        var response = agent.initialize();

        assertEquals(AcpSchema.LATEST_PROTOCOL_VERSION, response.protocolVersion());
        assertEquals(Boolean.TRUE, response.agentCapabilities().loadSession());
        assertTrue(response.agentCapabilities().promptCapabilities().embeddedContext());
        assertTrue(response.agentCapabilities().meta().containsKey("brokk"));
        assertTrue(response.agentCapabilities().sessionCapabilities().list() != null);
        assertTrue(response.agentCapabilities().sessionCapabilities().resume() != null);
        assertTrue(response.agentCapabilities().sessionCapabilities().close() != null);
        assertTrue(response.agentCapabilities().sessionCapabilities().fork() != null);
    }

    @Test
    void listSessionsReturnsBrokkSessionsAndHonorsCwdFilter() {
        var first = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        var second = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));

        var matching = agent.listSessions(new AcpProtocol.ListSessionsRequest(null, projectRoot.toString(), null));
        assertTrue(matching.sessions().stream().anyMatch(s -> s.sessionId().equals(first.sessionId())));
        assertTrue(matching.sessions().stream().anyMatch(s -> s.sessionId().equals(second.sessionId())));
        assertTrue(matching.sessions().stream().allMatch(s -> s.cwd().equals(projectRoot.toString())));
        assertTrue(matching.sessions().stream().allMatch(s -> s.updatedAt() != null));

        var otherRoot = projectRoot.resolveSibling(projectRoot.getFileName() + "-other");
        var mismatched = agent.listSessions(new AcpProtocol.ListSessionsRequest(null, otherRoot.toString(), null));
        assertTrue(mismatched.sessions().isEmpty());
    }

    @Test
    void resumeSessionSwitchesWithoutReplayingConversation() {
        var first = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        var second = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));

        var replayed = new ArrayList<AcpSchema.SessionUpdate>();
        agent.setSessionUpdateSender((sessionId, update) -> replayed.add(update));

        var response = agent.resumeSession(
                new AcpProtocol.ResumeSessionRequest(first.sessionId(), projectRoot.toString(), null, null));

        assertEquals(first.sessionId(), contextManager.getCurrentSessionId().toString());
        assertEquals("LUTZ", response.modes().currentModeId());
        assertTrue(response.models() != null);
        assertTrue(replayed.stream().allMatch(update -> update instanceof AcpSchema.AvailableCommandsUpdate));

        agent.resumeSession(
                new AcpProtocol.ResumeSessionRequest(second.sessionId(), projectRoot.toString(), null, null));
        assertEquals(second.sessionId(), contextManager.getCurrentSessionId().toString());
    }

    @Test
    void closeSessionClearsAcpStateButDoesNotDeleteBrokkSession() {
        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        agent.setMode(new AcpSchema.SetSessionModeRequest(created.sessionId(), "ASK"));
        var askResume = agent.resumeSession(
                new AcpProtocol.ResumeSessionRequest(created.sessionId(), projectRoot.toString(), null, null));
        assertEquals("ASK", askResume.modes().currentModeId());

        var close = agent.closeSession(new AcpProtocol.CloseSessionRequest(created.sessionId(), null));
        assertNull(close.meta());

        var listed = agent.listSessions(new AcpProtocol.ListSessionsRequest(null, projectRoot.toString(), null));
        assertTrue(listed.sessions().stream().anyMatch(s -> s.sessionId().equals(created.sessionId())));

        var resumed = agent.resumeSession(
                new AcpProtocol.ResumeSessionRequest(created.sessionId(), projectRoot.toString(), null, null));
        assertEquals("LUTZ", resumed.modes().currentModeId());
    }

    @Test
    void forkSessionCopiesBrokkSessionAndSwitchesToFork() {
        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        agent.setMode(new AcpSchema.SetSessionModeRequest(created.sessionId(), "ASK"));

        var forked = agent.forkSession(
                new AcpProtocol.ForkSessionRequest(created.sessionId(), projectRoot.toString(), null, null));

        assertNotEquals(created.sessionId(), forked.sessionId());
        assertEquals(forked.sessionId(), contextManager.getCurrentSessionId().toString());
        assertEquals("ASK", forked.modes().currentModeId());

        var listed = agent.listSessions(new AcpProtocol.ListSessionsRequest(null, projectRoot.toString(), null));
        assertTrue(listed.sessions().stream().anyMatch(s -> s.sessionId().equals(created.sessionId())));
        assertTrue(listed.sessions().stream().anyMatch(s -> s.sessionId().equals(forked.sessionId())));
    }

    @Test
    void runtimeRoutesNewSessionMethods() {
        var transport = new FakeTransport();
        try (var runtime = new BrokkAcpRuntime(transport, agent)) {
            var initialize = transport.exchange(AcpSchema.METHOD_INITIALIZE, "init", Map.of("protocolVersion", 1));
            assertNull(initialize.error());
            assertInstanceOf(AcpProtocol.InitializeResponse.class, initialize.result());

            var newSession = transport.exchange(
                    AcpSchema.METHOD_SESSION_NEW,
                    "new",
                    Map.of("cwd", projectRoot.toString(), "mcpServers", List.of()));
            var newSessionResult = assertInstanceOf(AcpSchema.NewSessionResponse.class, newSession.result());

            var list =
                    transport.exchange(AcpProtocol.METHOD_SESSION_LIST, "list", Map.of("cwd", projectRoot.toString()));
            var listResult = assertInstanceOf(AcpProtocol.ListSessionsResponse.class, list.result());
            assertTrue(
                    listResult.sessions().stream().anyMatch(s -> s.sessionId().equals(newSessionResult.sessionId())));

            var resume = transport.exchange(
                    AcpProtocol.METHOD_SESSION_RESUME,
                    "resume",
                    Map.of("sessionId", newSessionResult.sessionId(), "cwd", projectRoot.toString()));
            assertInstanceOf(AcpProtocol.ResumeSessionResponse.class, resume.result());

            var close = transport.exchange(
                    AcpProtocol.METHOD_SESSION_CLOSE, "close", Map.of("sessionId", newSessionResult.sessionId()));
            assertInstanceOf(AcpProtocol.CloseSessionResponse.class, close.result());

            var fork = transport.exchange(
                    AcpProtocol.METHOD_SESSION_FORK,
                    "fork",
                    Map.of("sessionId", newSessionResult.sessionId(), "cwd", projectRoot.toString()));
            assertInstanceOf(AcpProtocol.ForkSessionResponse.class, fork.result());
        }
    }

    private static final class FakeTransport implements AcpAgentTransport {
        private final McpJsonMapper mapper = McpJsonDefaults.getMapper();
        private final List<AcpSchema.JSONRPCMessage> sentMessages = new ArrayList<>();
        private Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler =
                ignored -> Mono.empty();

        AcpSchema.JSONRPCResponse exchange(String method, Object id, Object params) {
            var request = new AcpSchema.JSONRPCRequest(method, id, params);
            var response = handler.apply(Mono.just(request)).block();
            return assertInstanceOf(AcpSchema.JSONRPCResponse.class, response);
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
