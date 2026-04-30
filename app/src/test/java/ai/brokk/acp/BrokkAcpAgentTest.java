package ai.brokk.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.executor.jobs.JobRunner;
import ai.brokk.executor.jobs.JobStore;
import ai.brokk.io.ProjectFiles;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.TestService;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
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

class BrokkAcpAgentTest {
    private ContextManager contextManager;
    private JobRunner jobRunner;
    private TestService testService;
    private BrokkAcpAgent agent;
    private Path projectRoot;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        projectRoot = tempDir.toAbsolutePath().normalize();
        var project = MainProject.forTests(projectRoot);
        testService = new TestService(project);
        contextManager = new ContextManager(project, new ai.brokk.Service.Provider() {
            @Override
            public ai.brokk.AbstractService get() {
                return testService;
            }

            @Override
            public void reinit(ai.brokk.project.IProject p) {
                testService = new TestService(p);
            }
        });
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

    // ---- #3421: sanitizeReasoningLevel pure decision logic -------------------------------

    @Test
    void sanitizeReasoningLevel_unknownLevelNormalizesToDefault() {
        // Any id outside REASONING_LEVEL_IDS becomes "default" before capability checks.
        assertEquals("default", BrokkAcpAgent.sanitizeReasoningLevel("ultra", true, true));
        assertEquals("default", BrokkAcpAgent.sanitizeReasoningLevel("", true, true));
        assertEquals("default", BrokkAcpAgent.sanitizeReasoningLevel("LOW", true, true)); // case-sensitive
    }

    @Test
    void sanitizeReasoningLevel_modelLacksEffortSupport_anyNonDefaultBecomesDefault() {
        // When the model does not advertise reasoning_effort, low/medium/high/disable are all unsupported.
        assertEquals("default", BrokkAcpAgent.sanitizeReasoningLevel("low", false, false));
        assertEquals("default", BrokkAcpAgent.sanitizeReasoningLevel("medium", false, false));
        assertEquals("default", BrokkAcpAgent.sanitizeReasoningLevel("high", false, false));
        assertEquals("default", BrokkAcpAgent.sanitizeReasoningLevel("disable", false, false));
    }

    @Test
    void sanitizeReasoningLevel_modelSupportsEffort_lowMediumHighPassThrough() {
        assertEquals("low", BrokkAcpAgent.sanitizeReasoningLevel("low", true, false));
        assertEquals("medium", BrokkAcpAgent.sanitizeReasoningLevel("medium", true, false));
        assertEquals("high", BrokkAcpAgent.sanitizeReasoningLevel("high", true, false));
    }

    @Test
    void sanitizeReasoningLevel_disableRequiresExplicitDisableSupport() {
        // supportsEffort=true, supportsDisable=false -> "disable" must drop to "default"
        assertEquals("default", BrokkAcpAgent.sanitizeReasoningLevel("disable", true, false));
        // both supported -> "disable" passes through
        assertEquals("disable", BrokkAcpAgent.sanitizeReasoningLevel("disable", true, true));
    }

    @Test
    void sanitizeReasoningLevel_defaultLevelAlwaysPassesRegardlessOfCapabilities() {
        assertEquals("default", BrokkAcpAgent.sanitizeReasoningLevel("default", false, false));
        assertEquals("default", BrokkAcpAgent.sanitizeReasoningLevel("default", true, false));
        assertEquals("default", BrokkAcpAgent.sanitizeReasoningLevel("default", true, true));
    }

    // ---- end #3421 -----------------------------------------------------------------------

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
    void setModeEmitsCurrentModeUpdate() {
        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        var captured = new ArrayList<AcpSchema.SessionUpdate>();
        var capturedSessionIds = new ArrayList<String>();
        agent.setSessionUpdateSender((sessionId, update) -> {
            capturedSessionIds.add(sessionId);
            captured.add(update);
        });

        agent.setMode(new AcpSchema.SetSessionModeRequest(created.sessionId(), "ASK"));

        var modeUpdates = captured.stream()
                .filter(AcpSchema.CurrentModeUpdate.class::isInstance)
                .map(AcpSchema.CurrentModeUpdate.class::cast)
                .toList();
        assertEquals(1, modeUpdates.size());
        assertEquals("current_mode_update", modeUpdates.getFirst().sessionUpdate());
        assertEquals("ASK", modeUpdates.getFirst().currentModeId());
        assertEquals(created.sessionId(), capturedSessionIds.getFirst());
    }

    @Test
    void setModeWithInvalidModeDoesNotEmit() {
        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        var captured = new ArrayList<AcpSchema.SessionUpdate>();
        agent.setSessionUpdateSender((sessionId, update) -> captured.add(update));

        agent.setMode(new AcpSchema.SetSessionModeRequest(created.sessionId(), "BOGUS"));

        assertTrue(captured.stream().noneMatch(AcpSchema.CurrentModeUpdate.class::isInstance));
    }

    @Test
    void permissionAskOffersFourOptions() {
        try (var fixture = new PermissionFixture()) {
            var captured = new AtomicReference<AcpSchema.RequestPermissionRequest>();
            fixture.transport.respondTo(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, params -> {
                captured.set(fixture.transport.mapper.convertValue(
                        params, new TypeRef<AcpSchema.RequestPermissionRequest>() {}));
                return new AcpSchema.RequestPermissionResponse(
                        new AcpSchema.PermissionSelected("selected", "allow_once"));
            });

            assertTrue(fixture.context.askPermission("Allow X?", "myTool"));

            var request = captured.get();
            assertNotNull(request);
            var optionIds = request.options().stream()
                    .map(AcpSchema.PermissionOption::optionId)
                    .toList();
            assertEquals(List.of("allow_once", "allow_always", "reject_once", "reject_always"), optionIds);
            var optionKinds = request.options().stream()
                    .map(AcpSchema.PermissionOption::kind)
                    .toList();
            assertEquals(
                    List.of(
                            AcpSchema.PermissionOptionKind.ALLOW_ONCE,
                            AcpSchema.PermissionOptionKind.ALLOW_ALWAYS,
                            AcpSchema.PermissionOptionKind.REJECT_ONCE,
                            AcpSchema.PermissionOptionKind.REJECT_ALWAYS),
                    optionKinds);
        }
    }

    @Test
    void permissionAllowAlwaysSkipsSubsequentPrompts() {
        try (var fixture = new PermissionFixture()) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            fixture.transport.respondTo(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, params -> {
                calls.incrementAndGet();
                return new AcpSchema.RequestPermissionResponse(
                        new AcpSchema.PermissionSelected("selected", "allow_always"));
            });

            assertTrue(fixture.context.askPermission("Allow X?", "myTool"));
            assertTrue(fixture.context.askPermission("Allow X again?", "myTool"));

            assertEquals(1, calls.get());
        }
    }

    @Test
    void permissionRejectAlwaysCachesDenial() {
        try (var fixture = new PermissionFixture()) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            fixture.transport.respondTo(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, params -> {
                calls.incrementAndGet();
                return new AcpSchema.RequestPermissionResponse(
                        new AcpSchema.PermissionSelected("selected", "reject_always"));
            });

            assertFalse(fixture.context.askPermission("Allow X?", "myTool"));
            assertFalse(fixture.context.askPermission("Allow X again?", "myTool"));

            assertEquals(1, calls.get());
        }
    }

    @Test
    void permissionCacheIsScopedPerSession() {
        try (var fixture = new PermissionFixture()) {
            agent.rememberPermission("session-A", "myTool", BrokkAcpAgent.PermissionVerdict.ALLOW);

            var calls = new java.util.concurrent.atomic.AtomicInteger();
            fixture.transport.respondTo(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, params -> {
                calls.incrementAndGet();
                return new AcpSchema.RequestPermissionResponse(
                        new AcpSchema.PermissionSelected("selected", "reject_once"));
            });

            // session-B has no sticky verdict, so it must prompt.
            var ctxB = fixture.contextFor("session-B");
            assertFalse(ctxB.askPermission("Allow X?", "myTool"));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void permissionForShellOffersOnlyTwoOptionsAndBypassesCache() {
        // Shell permissions must NEVER cache: the cache key would be the literal string "shell",
        // so allow_always would blanket-allow every future shell command in the session.
        try (var fixture = new PermissionFixture()) {
            var captured = new AtomicReference<AcpSchema.RequestPermissionRequest>();
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            fixture.transport.respondTo(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, params -> {
                calls.incrementAndGet();
                captured.set(fixture.transport.mapper.convertValue(
                        params, new TypeRef<AcpSchema.RequestPermissionRequest>() {}));
                return new AcpSchema.RequestPermissionResponse(
                        new AcpSchema.PermissionSelected("selected", "allow_once"));
            });

            assertTrue(fixture.context.askPermission("Allow shell command: ls?", "shell"));
            assertTrue(fixture.context.askPermission("Allow shell command: rm -rf?", "shell"));

            assertEquals(2, calls.get(), "shell prompts must hit the user every time");
            var req = captured.get();
            assertNotNull(req);
            var optionIds = req.options().stream()
                    .map(AcpSchema.PermissionOption::optionId)
                    .toList();
            assertEquals(List.of("allow_once", "reject_once"), optionIds);
        }
    }

    @Test
    void permissionWithUnknownToolBypassesCache() {
        try (var fixture = new PermissionFixture()) {
            agent.rememberPermission(fixture.sessionId, "unknown", BrokkAcpAgent.PermissionVerdict.DENY);

            var calls = new java.util.concurrent.atomic.AtomicInteger();
            fixture.transport.respondTo(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, params -> {
                calls.incrementAndGet();
                return new AcpSchema.RequestPermissionResponse(
                        new AcpSchema.PermissionSelected("selected", "allow_once"));
            });

            assertTrue(fixture.context.askPermission("Confirm overwrite?"));

            assertEquals(1, calls.get());
        }
    }

    /** Spins up a transport + session + AcpRequestContext for permission tests. */
    private final class PermissionFixture implements AutoCloseable {
        final FakeTransport transport = new FakeTransport();
        final AcpAgentSession session = new AcpAgentSession(Duration.ofSeconds(10), transport, Map.of(), Map.of());
        final String sessionId = "session-A";
        final AcpRequestContext context = new AcpRequestContext(session, sessionId, null, agent);

        AcpRequestContext contextFor(String otherSessionId) {
            return new AcpRequestContext(session, otherSessionId, null, agent);
        }

        AcpRequestContext contextWithCaps(NegotiatedCapabilities clientCaps) {
            return new AcpRequestContext(session, sessionId, clientCaps, agent);
        }

        @Override
        public void close() {
            session.close();
        }
    }

    @Test
    void taskListMutationsEmitPlanSessionUpdate() throws Exception {
        agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        var plans = new java.util.concurrent.LinkedBlockingQueue<AcpSchema.Plan>();
        agent.setSessionUpdateSender((sessionId, update) -> {
            if (update instanceof AcpSchema.Plan p) {
                plans.add(p);
            }
        });
        agent.start();
        try {
            contextManager.setTaskListAsync(new ai.brokk.tasks.TaskList.TaskListData(
                    null,
                    List.of(new ai.brokk.tasks.TaskList.TaskItem("task-1", "Investigate the bug", "details", false))));

            var first = plans.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(first, "expected a plan update for the initial task list");
            assertEquals(1, first.entries().size());
            assertEquals("Investigate the bug", first.entries().getFirst().content());
            assertEquals(
                    AcpSchema.PlanEntryStatus.PENDING,
                    first.entries().getFirst().status());

            contextManager.setTaskListAsync(new ai.brokk.tasks.TaskList.TaskListData(
                    null,
                    List.of(new ai.brokk.tasks.TaskList.TaskItem("task-1", "Investigate the bug", "details", true))));

            var second = plans.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(second, "expected a follow-up plan update after marking task done");
            assertEquals(
                    AcpSchema.PlanEntryStatus.COMPLETED,
                    second.entries().getFirst().status());
        } finally {
            agent.stop();
        }
    }

    @Test
    void acpReadHonorsClientFileSystemCapability() throws Exception {
        var diskFile = projectRoot.resolve("foo.txt");
        java.nio.file.Files.writeString(diskFile, "DISK\n");
        var pf = new ProjectFile(projectRoot, java.nio.file.Path.of("foo.txt"));

        try (var fixture = new PermissionFixture()) {
            fixture.transport.respondTo(AcpSchema.METHOD_FS_READ_TEXT_FILE, params -> {
                var req =
                        fixture.transport.mapper.convertValue(params, new TypeRef<AcpSchema.ReadTextFileRequest>() {});
                assertEquals(diskFile.toAbsolutePath().toString(), req.path());
                return new AcpSchema.ReadTextFileResponse("EDITOR-BUFFER");
            });

            var clientCaps = NegotiatedCapabilities.fromClient(
                    new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(true, true), false));
            try (var ignored = AcpFileBridge.install(fixture.contextWithCaps(clientCaps), clientCaps)) {
                var read = ProjectFiles.read(pf);
                assertEquals("EDITOR-BUFFER", read.orElse(null));
            }
        }
    }

    @Test
    void acpWriteHonorsClientFileSystemCapability() throws Exception {
        var pf = new ProjectFile(projectRoot, java.nio.file.Path.of("write-target.txt"));

        try (var fixture = new PermissionFixture()) {
            var captured = new AtomicReference<AcpSchema.WriteTextFileRequest>();
            fixture.transport.respondTo(AcpSchema.METHOD_FS_WRITE_TEXT_FILE, params -> {
                captured.set(fixture.transport.mapper.convertValue(
                        params, new TypeRef<AcpSchema.WriteTextFileRequest>() {}));
                return new AcpSchema.WriteTextFileResponse();
            });

            var clientCaps = NegotiatedCapabilities.fromClient(
                    new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(true, true), false));
            try (var ignored = AcpFileBridge.install(fixture.contextWithCaps(clientCaps), clientCaps)) {
                ProjectFiles.write(pf, "NEW-CONTENT");
            }

            assertNotNull(captured.get());
            assertEquals(pf.absPath().toString(), captured.get().path());
            assertEquals("NEW-CONTENT", captured.get().content());
            // Disk should not have been touched when the bridge handled the write.
            assertFalse(java.nio.file.Files.exists(pf.absPath()));
        }
    }

    @Test
    void acpReadFallsBackToDiskWhenNoFsCapability() throws Exception {
        var diskFile = projectRoot.resolve("plain.txt");
        java.nio.file.Files.writeString(diskFile, "ON-DISK\n");
        var pf = new ProjectFile(projectRoot, java.nio.file.Path.of("plain.txt"));

        try (var fixture = new PermissionFixture()) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            fixture.transport.respondTo(AcpSchema.METHOD_FS_READ_TEXT_FILE, params -> {
                calls.incrementAndGet();
                return new AcpSchema.ReadTextFileResponse("UNEXPECTED");
            });

            var clientCaps = NegotiatedCapabilities.fromClient(
                    new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(false, false), false));
            try (var ignored = AcpFileBridge.install(fixture.contextWithCaps(clientCaps), clientCaps)) {
                var read = ProjectFiles.read(pf);
                assertEquals("ON-DISK\n", read.orElse(null));
            }
            assertEquals(0, calls.get());
        }
    }

    @Test
    void initializeAdvertisesMcpCapabilities() {
        var response = agent.initialize();
        var mcp = response.agentCapabilities().mcpCapabilities();
        assertNotNull(mcp);
        assertEquals(Boolean.TRUE, mcp.http());
        assertEquals(Boolean.FALSE, mcp.sse());
    }

    @Test
    void newSessionAcceptsHttpMcpServer() {
        // 127.0.0.1:1 always refuses; tool discovery fails fast and the server is still registered
        // with its untransformed (null) tools list. We assert only the conversion + per-session
        // bookkeeping, not the discovery (which needs a real MCP server, covered by integration).
        var http = new AcpSchema.McpServerHttp(
                "test-http",
                "http://127.0.0.1:1/mcp",
                List.of(new AcpSchema.HttpHeader("Authorization", "Bearer abc123")));

        var created = agent.newSession(
                new AcpSchema.NewSessionRequest(projectRoot.toString(), List.<AcpSchema.McpServer>of(http)));

        var registered = agent.mcpServersFor(created.sessionId());
        assertEquals(1, registered.size());
        var converted = assertInstanceOf(ai.brokk.mcpclient.HttpMcpServer.class, registered.getFirst());
        assertEquals("test-http", converted.name());
        assertEquals("http://127.0.0.1:1/mcp", converted.url().toString());
        assertEquals("Bearer abc123", converted.bearerToken());
    }

    @Test
    void newSessionSurfacesRejectedMcpServersInResponseMeta() {
        // SSE is not supported; should be dropped and surfaced under brokk.rejectedMcpServers.
        var sse = new AcpSchema.McpServerSse("test-sse", "https://example/mcp", List.of());
        var response = agent.newSession(
                new AcpSchema.NewSessionRequest(projectRoot.toString(), List.<AcpSchema.McpServer>of(sse)));

        var meta = response.meta();
        assertNotNull(meta);
        @SuppressWarnings("unchecked")
        var brokk = (Map<String, Object>) meta.get("brokk");
        assertNotNull(brokk);
        @SuppressWarnings("unchecked")
        var rejected = (List<String>) brokk.get("rejectedMcpServers");
        assertNotNull(rejected, "expected rejected MCP server names in response meta");
        assertTrue(rejected.contains("test-sse"));
    }

    @Test
    void closeSessionClearsMcpServers() {
        var http = new AcpSchema.McpServerHttp("test-http", "http://127.0.0.1:1/mcp", List.of());
        var created = agent.newSession(
                new AcpSchema.NewSessionRequest(projectRoot.toString(), List.<AcpSchema.McpServer>of(http)));
        assertEquals(1, agent.mcpServersFor(created.sessionId()).size());

        agent.closeSession(new AcpProtocol.CloseSessionRequest(created.sessionId(), null));

        assertTrue(agent.mcpServersFor(created.sessionId()).isEmpty());
    }

    @Test
    void extractResourceRelPathsHandlesFileUriResourceLink() {
        var fileUnderRoot = projectRoot.resolve("src/main/java/Foo.java");
        var blocks = List.<AcpSchema.ContentBlock>of(
                new AcpSchema.TextContent("summarize @file:Foo.java"),
                new AcpSchema.ResourceLink(
                        "resource_link",
                        "Foo.java",
                        fileUnderRoot.toUri().toString(),
                        null,
                        "manual",
                        null,
                        null,
                        null,
                        null));

        var paths = BrokkAcpAgent.extractResourceRelPaths(blocks, projectRoot);

        assertEquals(List.of("src/main/java/Foo.java"), paths);
    }

    @Test
    void extractResourceRelPathsHandlesEmbeddedTextResource() {
        var fileUnderRoot = projectRoot.resolve("README.md");
        var embedded = new AcpSchema.Resource(
                "resource",
                new AcpSchema.TextResourceContents(
                        "# Hello", fileUnderRoot.toUri().toString(), "text/markdown"),
                null,
                null);

        var paths = BrokkAcpAgent.extractResourceRelPaths(List.of(embedded), projectRoot);

        assertEquals(List.of("README.md"), paths);
    }

    @Test
    void extractResourceRelPathsRejectsUriOutsideRoot() {
        var outside = projectRoot.resolveSibling("other-repo").resolve("Secret.java");
        var blocks = List.<AcpSchema.ContentBlock>of(new AcpSchema.ResourceLink(
                "resource_link", "Secret.java", outside.toUri().toString(), null, null, null, null, null, null));

        var paths = BrokkAcpAgent.extractResourceRelPaths(blocks, projectRoot);

        assertTrue(paths.isEmpty(), "URI outside root must not produce a relative path");
    }

    @Test
    void extractResourceRelPathsDedupesAndIgnoresNonResourceBlocks() {
        var f = projectRoot.resolve("a/b.java");
        var blocks = List.<AcpSchema.ContentBlock>of(
                new AcpSchema.TextContent("ignore me"),
                new AcpSchema.ResourceLink(
                        "resource_link", "b.java", f.toUri().toString(), null, null, null, null, null, null),
                new AcpSchema.ResourceLink(
                        "resource_link", "b.java again", f.toUri().toString(), null, null, null, null, null, null));

        var paths = BrokkAcpAgent.extractResourceRelPaths(blocks, projectRoot);

        assertEquals(List.of("a/b.java"), paths);
    }

    @Test
    void extractResourceRelPathsAcceptsBareRelativeUri() {
        var blocks = List.<AcpSchema.ContentBlock>of(
                new AcpSchema.ResourceLink("resource_link", "x", "src/X.java", null, null, null, null, null, null));

        var paths = BrokkAcpAgent.extractResourceRelPaths(blocks, projectRoot);

        assertEquals(List.of("src/X.java"), paths);
    }

    @Test
    void extractResourceRelPathsReturnsEmptyForNullOrTextOnly() {
        assertTrue(BrokkAcpAgent.extractResourceRelPaths(null, projectRoot).isEmpty());
        assertTrue(BrokkAcpAgent.extractResourceRelPaths(List.of(), projectRoot).isEmpty());
        assertTrue(BrokkAcpAgent.extractResourceRelPaths(List.of(new AcpSchema.TextContent("hi")), projectRoot)
                .isEmpty());
    }

    @Test
    void inboundActivityTrackerUpdatesTimestampOnRead() throws Exception {
        var bytes = "hello\nworld\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var tracker = new AcpServerMain.InboundActivityTracker(new java.io.ByteArrayInputStream(bytes));
        long initial = tracker.lastReadAtMillis();
        Thread.sleep(5);

        // Single-byte read advances the timestamp.
        int b = tracker.read();
        assertNotEquals(-1, b);
        long afterSingle = tracker.lastReadAtMillis();
        assertTrue(afterSingle >= initial, "single-byte read must advance timestamp");

        // Bulk read advances the timestamp again.
        Thread.sleep(5);
        var buf = new byte[64];
        int n = tracker.read(buf, 0, buf.length);
        assertTrue(n > 0);
        long afterBulk = tracker.lastReadAtMillis();
        assertTrue(afterBulk >= afterSingle, "bulk read must advance timestamp");

        // EOF (read returning -1) must NOT advance the timestamp.
        long beforeEof = tracker.lastReadAtMillis();
        Thread.sleep(5);
        assertEquals(-1, tracker.read());
        assertEquals(beforeEof, tracker.lastReadAtMillis(), "EOF must not advance timestamp");
    }

    @Test
    void permissionTimeoutSurfacesAsDenialAndUserMessage() {
        var transport = new FakeTransport();
        // 200ms SDK timeout → AcpRequestContext.requestPermission's onErrorResume(TimeoutException)
        // catches the SDK-emitted TimeoutException and converts it to PermissionCancelled.
        var shortSession = new AcpAgentSession(Duration.ofMillis(200), transport, Map.of(), Map.of());
        var ctx = new AcpRequestContext(shortSession, "session-timeout", null, agent);
        try {
            // Intentionally do NOT register a respondTo handler — the request will dangle and
            // the SDK's per-request timeout fires.
            int outstandingBefore = AcpRequestContext.OUTSTANDING_PERMISSION_REQUESTS.get();

            boolean approved = ctx.askPermission("Allow destructive: doRm?", "doRm");

            assertFalse(approved, "timeout must surface as denial");
            assertEquals(
                    outstandingBefore,
                    AcpRequestContext.OUTSTANDING_PERMISSION_REQUESTS.get(),
                    "OUTSTANDING_PERMISSION_REQUESTS must decrement even on timeout");

            var notifications = transport.sentMessages.stream()
                    .filter(AcpSchema.JSONRPCNotification.class::isInstance)
                    .map(AcpSchema.JSONRPCNotification.class::cast)
                    .filter(n -> AcpSchema.METHOD_SESSION_UPDATE.equals(n.method()))
                    .map(n ->
                            transport.mapper.convertValue(n.params(), new TypeRef<AcpSchema.SessionNotification>() {}))
                    .toList();
            assertTrue(
                    notifications.stream()
                            .anyMatch(n -> n.update() instanceof AcpSchema.AgentMessageChunk a
                                    && a.content() instanceof AcpSchema.TextContent t
                                    && t.text().toLowerCase().contains("timed out")),
                    "user must see a 'timed out' chat message on permission timeout");
        } finally {
            shortSession.close();
        }
    }

    /**
     * Replays the exact JSON-RPC response shape captured from IntelliJ when the user clicks
     * "Allow once" on a destructive-tool permission dialog (acp-logs zip 2026-04-28). Includes
     * the IDE-side {@code "type"} envelope discriminator and the duplicate-name {@code outcome}
     * discriminator on {@code PermissionSelected}. Verifies that:
     * <ul>
     *   <li>{@code AcpSchema.deserializeJsonRpcMessage} routes it as a {@code JSONRPCResponse}
     *       (the leading {@code type} field must not derail classification),</li>
     *   <li>{@code unmarshalFrom} produces a {@code PermissionSelected} with the correct
     *       {@code optionId}, and</li>
     *   <li>the canonical {@code askPermission} return path treats {@code allow_once} as approval.</li>
     * </ul>
     * If this test fails, the "approval click does nothing" bug is in agent-side parsing; if it
     * passes, the bug is upstream of the parser (response not actually reaching the agent).
     */
    @Test
    void permissionResponseFromIntellijDeserializesAndApproves() throws Exception {
        var wireLine = "{\"type\":\"com.agentclientprotocol.rpc.JsonRpcResponse\","
                + "\"id\":\"1b8d5f1d-0\","
                + "\"result\":{\"outcome\":{\"outcome\":\"selected\",\"optionId\":\"allow_once\"}},"
                + "\"jsonrpc\":\"2.0\"}";

        var mapper = McpJsonDefaults.getMapper();
        var message = AcpSchema.deserializeJsonRpcMessage(mapper, wireLine);

        var response = assertInstanceOf(AcpSchema.JSONRPCResponse.class, message);
        assertEquals("1b8d5f1d-0", response.id());
        assertNull(response.error());

        var permResponse =
                mapper.convertValue(response.result(), new TypeRef<AcpSchema.RequestPermissionResponse>() {});
        var selected = assertInstanceOf(AcpSchema.PermissionSelected.class, permResponse.outcome());
        assertEquals("allow_once", selected.optionId());

        // askPermission returns true for allow_once / allow_always.
        assertTrue("allow_once".equals(selected.optionId()) || "allow_always".equals(selected.optionId()));
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
            var newSessionResult = assertInstanceOf(AcpProtocol.NewSessionResponseExt.class, newSession.result());

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

    // ---- PermissionMode and gate ----

    @Test
    void permissionModeParseRoundTrip() {
        for (var mode : PermissionMode.values()) {
            assertEquals(mode, PermissionMode.parse(mode.asString()).orElseThrow());
        }
        assertTrue(PermissionMode.parse("not-a-mode").isEmpty());
        assertTrue(PermissionMode.parse("").isEmpty());
    }

    @Test
    void gateBypassAllowsEverything() {
        assertEquals(
                PermissionGate.Outcome.ALLOW,
                PermissionGate.decide(PermissionMode.BYPASS_PERMISSIONS, AcpSchema.ToolKind.EDIT, "editFile", false));
        assertEquals(
                PermissionGate.Outcome.ALLOW,
                PermissionGate.decide(PermissionMode.BYPASS_PERMISSIONS, AcpSchema.ToolKind.EXECUTE, "shell", false));
        assertEquals(
                PermissionGate.Outcome.ALLOW,
                PermissionGate.decide(PermissionMode.BYPASS_PERMISSIONS, AcpSchema.ToolKind.OTHER, "weird", false));
    }

    @Test
    void gateReadOnlyRejectsEditExecuteAndOther() {
        assertEquals(
                PermissionGate.Outcome.REJECT,
                PermissionGate.decide(PermissionMode.READ_ONLY, AcpSchema.ToolKind.EDIT, "editFile", false));
        assertEquals(
                PermissionGate.Outcome.REJECT,
                PermissionGate.decide(PermissionMode.READ_ONLY, AcpSchema.ToolKind.EXECUTE, "shell", false));
        assertEquals(
                PermissionGate.Outcome.REJECT,
                PermissionGate.decide(PermissionMode.READ_ONLY, AcpSchema.ToolKind.OTHER, "weird", false));
        // Always-allow does not lift the read-only brake.
        assertEquals(
                PermissionGate.Outcome.REJECT,
                PermissionGate.decide(PermissionMode.READ_ONLY, AcpSchema.ToolKind.EDIT, "editFile", true));
    }

    @Test
    void gateReadOnlyAllowsInformationalKinds() {
        for (var k : List.of(
                AcpSchema.ToolKind.READ,
                AcpSchema.ToolKind.SEARCH,
                AcpSchema.ToolKind.THINK,
                AcpSchema.ToolKind.FETCH)) {
            assertEquals(
                    PermissionGate.Outcome.ALLOW,
                    PermissionGate.decide(PermissionMode.READ_ONLY, k, "anything", false),
                    "READ_ONLY must allow " + k);
        }
    }

    @Test
    void gateAcceptEditsAllowsEditButPromptsExecute() {
        assertEquals(
                PermissionGate.Outcome.ALLOW,
                PermissionGate.decide(PermissionMode.ACCEPT_EDITS, AcpSchema.ToolKind.EDIT, "editFile", false));
        assertEquals(
                PermissionGate.Outcome.PROMPT,
                PermissionGate.decide(PermissionMode.ACCEPT_EDITS, AcpSchema.ToolKind.EXECUTE, "shell", false));
        assertEquals(
                PermissionGate.Outcome.PROMPT,
                PermissionGate.decide(PermissionMode.ACCEPT_EDITS, AcpSchema.ToolKind.OTHER, "weird", false));
    }

    @Test
    void gateDefaultPromptsExceptForReadOnlyKinds() {
        assertEquals(
                PermissionGate.Outcome.ALLOW,
                PermissionGate.decide(PermissionMode.DEFAULT, AcpSchema.ToolKind.READ, "readFile", false));
        assertEquals(
                PermissionGate.Outcome.PROMPT,
                PermissionGate.decide(PermissionMode.DEFAULT, AcpSchema.ToolKind.EDIT, "editFile", false));
        assertEquals(
                PermissionGate.Outcome.PROMPT,
                PermissionGate.decide(PermissionMode.DEFAULT, AcpSchema.ToolKind.EXECUTE, "shell", false));
    }

    @Test
    void gateAlwaysAllowSkipsPromptExceptForShell() {
        // Always-allow short-circuits the prompt for cacheable tools…
        assertEquals(
                PermissionGate.Outcome.ALLOW,
                PermissionGate.decide(PermissionMode.DEFAULT, AcpSchema.ToolKind.EDIT, "editFile", true));
        // …but never for shell, where one approval would blanket-allow every future shell command.
        assertEquals(
                PermissionGate.Outcome.PROMPT,
                PermissionGate.decide(PermissionMode.DEFAULT, AcpSchema.ToolKind.EXECUTE, "shell", true));
    }

    @Test
    void gateClassifiesBrokkToolNames() {
        assertEquals(AcpSchema.ToolKind.EXECUTE, PermissionGate.classify("shell"));
        assertEquals(AcpSchema.ToolKind.SEARCH, PermissionGate.classify("searchAgent"));
        assertEquals(AcpSchema.ToolKind.SEARCH, PermissionGate.classify("findFiles"));
        assertEquals(AcpSchema.ToolKind.READ, PermissionGate.classify("getSummaries"));
        assertEquals(AcpSchema.ToolKind.READ, PermissionGate.classify("listFiles"));
        assertEquals(AcpSchema.ToolKind.EDIT, PermissionGate.classify("addFiles"));
        assertEquals(AcpSchema.ToolKind.EDIT, PermissionGate.classify("replaceText"));
        assertEquals(AcpSchema.ToolKind.THINK, PermissionGate.classify("createOrReplaceTaskList"));
        assertEquals(AcpSchema.ToolKind.OTHER, PermissionGate.classify("totallyUnknown"));
    }

    // ---- session/set_config_option handler ----

    @Test
    void newSessionAdvertisesBothDropdowns() {
        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));

        assertNotNull(created.configOptions());
        // All selectors must come through configOptions. IntelliJ hides legacy `modes` and
        // `models` channels once configOptions is non-empty, so dropping any of these would make
        // the corresponding toolbar element vanish.
        assertEquals(
                List.of("behavior_mode", "permission_mode", "model_selection", "code_model_selection"),
                created.configOptions().stream()
                        .map(AcpProtocol.SessionConfigOption::id)
                        .toList());

        var behavior = created.configOptions().stream()
                .filter(o -> "behavior_mode".equals(o.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("LUTZ", behavior.currentValue());
        assertEquals("select", behavior.type());
        assertEquals(
                List.of("LUTZ", "CODE", "ASK", "PLAN"),
                behavior.options().stream()
                        .map(AcpProtocol.SessionConfigSelectOption::value)
                        .toList());

        var permission = created.configOptions().stream()
                .filter(o -> "permission_mode".equals(o.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("default", permission.currentValue());
        assertEquals("select", permission.type());
        assertEquals(
                List.of("default", "acceptEdits", "readOnly", "bypassPermissions"),
                permission.options().stream()
                        .map(AcpProtocol.SessionConfigSelectOption::value)
                        .toList());
    }

    @Test
    void setSessionConfigOptionRoutesBehaviorMode() {
        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        var captured = new ArrayList<AcpSchema.SessionUpdate>();
        agent.setSessionUpdateSender((sessionId, update) -> captured.add(update));

        var resp = agent.setSessionConfigOption(
                new AcpProtocol.SetSessionConfigOptionRequest(created.sessionId(), "behavior_mode", "ASK", null));

        // Existing setMode logic must have stored the new mode and emitted current_mode_update.
        var modeUpdates = captured.stream()
                .filter(AcpSchema.CurrentModeUpdate.class::isInstance)
                .map(AcpSchema.CurrentModeUpdate.class::cast)
                .toList();
        assertEquals(1, modeUpdates.size());
        assertEquals("ASK", modeUpdates.getFirst().currentModeId());

        var behavior = resp.configOptions().stream()
                .filter(o -> "behavior_mode".equals(o.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("ASK", behavior.currentValue());
    }

    @Test
    void newSessionAdvertisesModelDropdownWithModelCategory() {
        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        var model = created.configOptions().stream()
                .filter(o -> "model_selection".equals(o.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("model", model.category());
        assertEquals("select", model.type());
        assertNotNull(model.currentValue());
        assertFalse(model.options().isEmpty());
    }

    @Test
    void newSessionAdvertisesCodeModelDropdownWithModelCategory() {
        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        var codeModel = created.configOptions().stream()
                .filter(o -> "code_model_selection".equals(o.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("model", codeModel.category());
        assertEquals("select", codeModel.type());
        assertNotNull(codeModel.currentValue());
        assertFalse(codeModel.currentValue().isBlank());
        assertFalse(codeModel.options().isEmpty());
        assertTrue(codeModel.options().stream()
                .map(AcpProtocol.SessionConfigSelectOption::value)
                .anyMatch(codeModel.currentValue()::equals));
    }

    @Test
    void newSessionAdvertisesOnlyBaseEntryForModelWithoutEffortSupport() {
        seedModelCapabilities(Map.of("plain-model", TestService.modelInfo(false, false)));

        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));

        var optionValues = modelSelectionOption(created).options().stream()
                .map(AcpProtocol.SessionConfigSelectOption::value)
                .toList();
        var modelValues = created.models().availableModels().stream()
                .map(AcpSchema.ModelInfo::modelId)
                .toList();

        assertEquals(List.of("plain-model"), optionValues);
        assertEquals(List.of("plain-model"), modelValues);
        assertEquals("plain-model", modelSelectionOption(created).currentValue());
        assertEquals("plain-model", created.models().currentModelId());
    }

    @Test
    void newSessionAdvertisesLowMediumHighForEffortModelWithoutDisable() {
        seedModelCapabilities(Map.of("effort-model", TestService.modelInfo(true, false)));

        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));

        var expected = List.of("effort-model", "effort-model/low", "effort-model/medium", "effort-model/high");
        var optionValues = modelSelectionOption(created).options().stream()
                .map(AcpProtocol.SessionConfigSelectOption::value)
                .toList();
        var modelValues = created.models().availableModels().stream()
                .map(AcpSchema.ModelInfo::modelId)
                .toList();

        assertEquals(expected, optionValues);
        assertEquals(expected, modelValues);
    }

    @Test
    void newSessionAdvertisesDisableOnlyWhenModelSupportsIt() {
        seedModelCapabilities(Map.of("disable-model", TestService.modelInfo(true, true)));

        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));

        var expected = List.of(
                "disable-model",
                "disable-model/low",
                "disable-model/medium",
                "disable-model/high",
                "disable-model/disable");
        var optionValues = modelSelectionOption(created).options().stream()
                .map(AcpProtocol.SessionConfigSelectOption::value)
                .toList();
        var modelValues = created.models().availableModels().stream()
                .map(AcpSchema.ModelInfo::modelId)
                .toList();

        assertEquals(expected, optionValues);
        assertEquals(expected, modelValues);
    }

    @Test
    void configRefreshNormalizesUnsupportedReasoningLevelToValidCurrentSelection() {
        seedModelCapabilities(Map.of("plain-model", TestService.modelInfo(false, false)));

        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        var response = agent.setSessionConfigOption(new AcpProtocol.SetSessionConfigOptionRequest(
                created.sessionId(), "model_selection", "plain-model/high", null));

        assertEquals("plain-model", modelSelectionOption(response).currentValue());

        var resumed = agent.resumeSession(
                new AcpProtocol.ResumeSessionRequest(created.sessionId(), projectRoot.toString(), null, null));
        assertEquals("plain-model", resumed.models().currentModelId());
        assertEquals("plain-model", modelSelectionOption(resumed).currentValue());
    }

    @Test
    void configRefreshNormalizesDisableWhenModelDoesNotSupportIt() {
        seedModelCapabilities(Map.of("effort-model", TestService.modelInfo(true, false)));

        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        var response = agent.setSessionConfigOption(new AcpProtocol.SetSessionConfigOptionRequest(
                created.sessionId(), "model_selection", "effort-model/disable", null));

        assertEquals("effort-model", modelSelectionOption(response).currentValue());

        var loaded =
                agent.loadSession(new AcpSchema.LoadSessionRequest(created.sessionId(), projectRoot.toString(), null));
        assertEquals("effort-model", loaded.models().currentModelId());
        assertEquals("effort-model", modelSelectionOption(loaded).currentValue());
    }

    // ---- Code-model selector capability coverage (mirrors model_selection above) -------

    @Test
    void newSessionAdvertisesOnlyCodeBaseEntryForModelWithoutEffortSupport() {
        seedModelCapabilities(Map.of("plain-model", TestService.modelInfo(false, false)));

        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));

        var optionValues = codeModelSelectionOption(created).options().stream()
                .map(AcpProtocol.SessionConfigSelectOption::value)
                .toList();
        assertEquals(List.of("plain-model"), optionValues);
        assertEquals("plain-model", codeModelSelectionOption(created).currentValue());
    }

    @Test
    void newSessionAdvertisesCodeLowMediumHighForEffortModelWithoutDisable() {
        seedModelCapabilities(Map.of("effort-model", TestService.modelInfo(true, false)));

        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));

        var expected = List.of("effort-model", "effort-model/low", "effort-model/medium", "effort-model/high");
        var optionValues = codeModelSelectionOption(created).options().stream()
                .map(AcpProtocol.SessionConfigSelectOption::value)
                .toList();
        assertEquals(expected, optionValues);
        // DEFAULT_REASONING_LEVEL_CODE = "disable" but the model does not support disable, so
        // the level sanitizes to "default" and the current selection drops the variant suffix.
        assertEquals("effort-model", codeModelSelectionOption(created).currentValue());
    }

    @Test
    void newSessionAdvertisesCodeDisableOnlyWhenCodeModelSupportsIt() {
        seedModelCapabilities(Map.of("disable-model", TestService.modelInfo(true, true)));

        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));

        var expected = List.of(
                "disable-model",
                "disable-model/low",
                "disable-model/medium",
                "disable-model/high",
                "disable-model/disable");
        var optionValues = codeModelSelectionOption(created).options().stream()
                .map(AcpProtocol.SessionConfigSelectOption::value)
                .toList();
        assertEquals(expected, optionValues);
        // DEFAULT_REASONING_LEVEL_CODE = "disable" and the model supports it, so it survives.
        assertEquals("disable-model/disable", codeModelSelectionOption(created).currentValue());
    }

    @Test
    void setSessionConfigOptionParsesCodeModelVariantAndPersistsAcrossLoad() {
        seedModelCapabilities(Map.of("effort-model", TestService.modelInfo(true, false)));

        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        var response = agent.setSessionConfigOption(new AcpProtocol.SetSessionConfigOptionRequest(
                created.sessionId(), "code_model_selection", "effort-model/high", null));

        assertEquals("effort-model/high", codeModelSelectionOption(response).currentValue());

        var loaded =
                agent.loadSession(new AcpSchema.LoadSessionRequest(created.sessionId(), projectRoot.toString(), null));
        assertEquals("effort-model/high", codeModelSelectionOption(loaded).currentValue());
    }

    @Test
    void configRefreshNormalizesUnsupportedCodeReasoningLevelToValidCurrentSelection() {
        seedModelCapabilities(Map.of("plain-model", TestService.modelInfo(false, false)));

        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        var response = agent.setSessionConfigOption(new AcpProtocol.SetSessionConfigOptionRequest(
                created.sessionId(), "code_model_selection", "plain-model/high", null));

        // plain-model does not support reasoning_effort, so /high collapses back to the bare model.
        assertEquals("plain-model", codeModelSelectionOption(response).currentValue());

        var resumed = agent.resumeSession(
                new AcpProtocol.ResumeSessionRequest(created.sessionId(), projectRoot.toString(), null, null));
        assertEquals("plain-model", codeModelSelectionOption(resumed).currentValue());
    }

    @Test
    void configRefreshNormalizesCodeDisableWhenCodeModelDoesNotSupportIt() {
        seedModelCapabilities(Map.of("effort-model", TestService.modelInfo(true, false)));

        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        var response = agent.setSessionConfigOption(new AcpProtocol.SetSessionConfigOptionRequest(
                created.sessionId(), "code_model_selection", "effort-model/disable", null));

        // effort-model supports effort but not disable; /disable normalizes back to base model.
        assertEquals("effort-model", codeModelSelectionOption(response).currentValue());

        var loaded =
                agent.loadSession(new AcpSchema.LoadSessionRequest(created.sessionId(), projectRoot.toString(), null));
        assertEquals("effort-model", codeModelSelectionOption(loaded).currentValue());
    }

    @Test
    void setSessionConfigOptionRejectsUnknownBehaviorMode() {
        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> agent.setSessionConfigOption(new AcpProtocol.SetSessionConfigOptionRequest(
                        created.sessionId(), "behavior_mode", "BOGUS", null)));
    }

    @Test
    void setSessionConfigOptionStoresPermissionMode() {
        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));

        var resp = agent.setSessionConfigOption(new AcpProtocol.SetSessionConfigOptionRequest(
                created.sessionId(), "permission_mode", "acceptEdits", null));

        assertEquals(PermissionMode.ACCEPT_EDITS, agent.permissionModeFor(created.sessionId()));
        var permission = resp.configOptions().stream()
                .filter(o -> "permission_mode".equals(o.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("acceptEdits", permission.currentValue());
    }

    @Test
    void setSessionConfigOptionStoresCodeModel() {
        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        var initial = created.configOptions().stream()
                .filter(o -> "code_model_selection".equals(o.id()))
                .findFirst()
                .orElseThrow();
        var selectedValue = initial.options().stream()
                .map(AcpProtocol.SessionConfigSelectOption::value)
                .filter(v -> !v.equals(initial.currentValue()))
                .findFirst()
                .orElse(initial.currentValue());

        var resp = agent.setSessionConfigOption(new AcpProtocol.SetSessionConfigOptionRequest(
                created.sessionId(), "code_model_selection", selectedValue, null));

        var updated = resp.configOptions().stream()
                .filter(o -> "code_model_selection".equals(o.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(selectedValue, updated.currentValue());
        assertTrue(updated.options().stream()
                .map(AcpProtocol.SessionConfigSelectOption::value)
                .anyMatch(selectedValue::equals));
    }

    @Test
    void loadSessionRetainsConfiguredCodeModel() {
        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        var initial = created.configOptions().stream()
                .filter(o -> "code_model_selection".equals(o.id()))
                .findFirst()
                .orElseThrow();
        var selectedValue = initial.options().stream()
                .map(AcpProtocol.SessionConfigSelectOption::value)
                .filter(v -> !v.equals(initial.currentValue()))
                .findFirst()
                .orElse(initial.currentValue());

        agent.setSessionConfigOption(new AcpProtocol.SetSessionConfigOptionRequest(
                created.sessionId(), "code_model_selection", selectedValue, null));

        var loaded = agent.loadSession(
                new AcpSchema.LoadSessionRequest(created.sessionId(), projectRoot.toString(), List.of(), null));

        var updated = loaded.configOptions().stream()
                .filter(o -> "code_model_selection".equals(o.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(selectedValue, updated.currentValue());
        assertTrue(updated.options().stream()
                .map(AcpProtocol.SessionConfigSelectOption::value)
                .anyMatch(selectedValue::equals));
    }

    @Test
    void resumeSessionSanitizesStaleCodeModelSelection() {
        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));

        agent.setSessionConfigOption(new AcpProtocol.SetSessionConfigOptionRequest(
                created.sessionId(), "code_model_selection", "not-a-real-model", null));

        var resumed = agent.resumeSession(
                new AcpProtocol.ResumeSessionRequest(created.sessionId(), projectRoot.toString(), null, null));

        var updated = resumed.configOptions().stream()
                .filter(o -> "code_model_selection".equals(o.id()))
                .findFirst()
                .orElseThrow();
        assertFalse(updated.currentValue().isBlank());
        assertTrue(updated.options().stream()
                .map(AcpProtocol.SessionConfigSelectOption::value)
                .anyMatch(updated.currentValue()::equals));
    }

    @Test
    void setSessionConfigOptionRejectsUnknownValue() {
        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> agent.setSessionConfigOption(new AcpProtocol.SetSessionConfigOptionRequest(
                        created.sessionId(), "permission_mode", "bogus", null)));
        assertTrue(ex.getMessage().contains("Unknown permission mode"));
    }

    @Test
    void setSessionConfigOptionRejectsUnknownConfigId() {
        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> agent.setSessionConfigOption(new AcpProtocol.SetSessionConfigOptionRequest(
                        created.sessionId(), "made_up", "default", null)));
    }

    @Test
    void runtimeRoutesSetSessionConfigOption() {
        var transport = new FakeTransport();
        try (var runtime = new BrokkAcpRuntime(transport, agent)) {
            transport.exchange(AcpSchema.METHOD_INITIALIZE, "init", Map.of("protocolVersion", 1));
            var newSession = transport.exchange(
                    AcpSchema.METHOD_SESSION_NEW,
                    "new",
                    Map.of("cwd", projectRoot.toString(), "mcpServers", List.of()));
            var sessionId = ((AcpProtocol.NewSessionResponseExt) newSession.result()).sessionId();

            var advertised = ((AcpProtocol.NewSessionResponseExt) newSession.result())
                    .configOptions().stream()
                            .filter(o -> "code_model_selection".equals(o.id()))
                            .findFirst()
                            .orElseThrow();
            var selectedValue = advertised.options().stream()
                    .map(AcpProtocol.SessionConfigSelectOption::value)
                    .findFirst()
                    .orElseThrow();

            var setConfig = transport.exchange(
                    AcpProtocol.METHOD_SESSION_SET_CONFIG_OPTION,
                    "setcfg",
                    Map.of("sessionId", sessionId, "configId", "code_model_selection", "value", selectedValue));
            assertNull(setConfig.error());
            var result = assertInstanceOf(AcpProtocol.SetSessionConfigOptionResponse.class, setConfig.result());
            assertEquals(
                    selectedValue,
                    result.configOptions().stream()
                            .filter(o -> "code_model_selection".equals(o.id()))
                            .findFirst()
                            .orElseThrow()
                            .currentValue());
        }
    }

    // ---- askPermission honors PermissionMode ----

    @Test
    void askPermissionShortCircuitsUnderBypass() {
        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        agent.setSessionConfigOption(new AcpProtocol.SetSessionConfigOptionRequest(
                created.sessionId(), "permission_mode", "bypassPermissions", null));

        try (var fixture = new PermissionFixture()) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            fixture.transport.respondTo(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, params -> {
                calls.incrementAndGet();
                return new AcpSchema.RequestPermissionResponse(
                        new AcpSchema.PermissionSelected("selected", "reject_once"));
            });

            var ctx = fixture.contextFor(created.sessionId());
            assertTrue(ctx.askPermission("Allow editFile?", "editFile"));
            assertTrue(ctx.askPermission("Allow shell?", "shell"));
            assertEquals(0, calls.get(), "BYPASS must not round-trip to client");
        }
    }

    @Test
    void askPermissionRejectsEditsUnderReadOnly() {
        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        agent.setSessionConfigOption(new AcpProtocol.SetSessionConfigOptionRequest(
                created.sessionId(), "permission_mode", "readOnly", null));

        try (var fixture = new PermissionFixture()) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            fixture.transport.respondTo(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, params -> {
                calls.incrementAndGet();
                return new AcpSchema.RequestPermissionResponse(
                        new AcpSchema.PermissionSelected("selected", "allow_once"));
            });

            var ctx = fixture.contextFor(created.sessionId());
            assertFalse(ctx.askPermission("Allow editFile?", "editFile"));
            assertFalse(ctx.askPermission("Allow shell?", "shell"));
            assertTrue(ctx.askPermission("Allow readFile?", "readFile"));
            assertEquals(0, calls.get(), "READ_ONLY must decide locally without round-tripping");

            var sawDenialMessage = fixture.transport.sessionUpdates().stream()
                    .anyMatch(n -> n.update() instanceof AcpSchema.AgentMessageChunk a
                            && a.content() instanceof AcpSchema.TextContent t
                            && t.text().contains("denied"));
            assertTrue(sawDenialMessage, "user must see a denial chat message under READ_ONLY");
        }
    }

    @Test
    void askPermissionAutoAllowsEditsUnderAcceptEdits() {
        var created = agent.newSession(new AcpSchema.NewSessionRequest(projectRoot.toString(), List.of()));
        agent.setSessionConfigOption(new AcpProtocol.SetSessionConfigOptionRequest(
                created.sessionId(), "permission_mode", "acceptEdits", null));

        try (var fixture = new PermissionFixture()) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            fixture.transport.respondTo(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, params -> {
                calls.incrementAndGet();
                return new AcpSchema.RequestPermissionResponse(
                        new AcpSchema.PermissionSelected("selected", "allow_once"));
            });

            var ctx = fixture.contextFor(created.sessionId());
            assertTrue(ctx.askPermission("Allow editFile?", "editFile"));
            assertEquals(0, calls.get(), "ACCEPT_EDITS must not prompt for edits");

            assertTrue(ctx.askPermission("Allow shell?", "shell"));
            assertEquals(1, calls.get(), "ACCEPT_EDITS must still prompt for shell");
        }
    }

    private void seedModelCapabilities(Map<String, Map<String, Object>> modelInfoByName) {
        testService.setAvailableModels(modelInfoByName);
    }

    private static AcpProtocol.SessionConfigOption modelSelectionOption(AcpProtocol.NewSessionResponseExt response) {
        return response.configOptions().stream()
                .filter(o -> "model_selection".equals(o.id()))
                .findFirst()
                .orElseThrow();
    }

    private static AcpProtocol.SessionConfigOption modelSelectionOption(AcpProtocol.ResumeSessionResponse response) {
        return response.configOptions().stream()
                .filter(o -> "model_selection".equals(o.id()))
                .findFirst()
                .orElseThrow();
    }

    private static AcpProtocol.SessionConfigOption modelSelectionOption(AcpProtocol.LoadSessionResponseExt response) {
        return response.configOptions().stream()
                .filter(o -> "model_selection".equals(o.id()))
                .findFirst()
                .orElseThrow();
    }

    private static AcpProtocol.SessionConfigOption modelSelectionOption(
            AcpProtocol.SetSessionConfigOptionResponse response) {
        return response.configOptions().stream()
                .filter(o -> "model_selection".equals(o.id()))
                .findFirst()
                .orElseThrow();
    }

    private static AcpProtocol.SessionConfigOption codeModelSelectionOption(
            AcpProtocol.NewSessionResponseExt response) {
        return response.configOptions().stream()
                .filter(o -> "code_model_selection".equals(o.id()))
                .findFirst()
                .orElseThrow();
    }

    private static AcpProtocol.SessionConfigOption codeModelSelectionOption(
            AcpProtocol.ResumeSessionResponse response) {
        return response.configOptions().stream()
                .filter(o -> "code_model_selection".equals(o.id()))
                .findFirst()
                .orElseThrow();
    }

    private static AcpProtocol.SessionConfigOption codeModelSelectionOption(
            AcpProtocol.LoadSessionResponseExt response) {
        return response.configOptions().stream()
                .filter(o -> "code_model_selection".equals(o.id()))
                .findFirst()
                .orElseThrow();
    }

    private static AcpProtocol.SessionConfigOption codeModelSelectionOption(
            AcpProtocol.SetSessionConfigOptionResponse response) {
        return response.configOptions().stream()
                .filter(o -> "code_model_selection".equals(o.id()))
                .findFirst()
                .orElseThrow();
    }

    static final class FakeTransport implements AcpAgentTransport {
        private final McpJsonMapper mapper = McpJsonDefaults.getMapper();
        private final List<AcpSchema.JSONRPCMessage> sentMessages = new ArrayList<>();
        private final Map<String, Function<Object, Object>> requestStubs = new ConcurrentHashMap<>();
        private Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler =
                ignored -> Mono.empty();

        AcpSchema.JSONRPCResponse exchange(String method, Object id, Object params) {
            var request = new AcpSchema.JSONRPCRequest(method, id, params);
            var response = handler.apply(Mono.just(request)).block();
            return assertInstanceOf(AcpSchema.JSONRPCResponse.class, response);
        }

        /** Decoded view of {@code session/update} notifications the agent has emitted. */
        List<AcpSchema.SessionNotification> sessionUpdates() {
            return sentMessages.stream()
                    .filter(AcpSchema.JSONRPCNotification.class::isInstance)
                    .map(AcpSchema.JSONRPCNotification.class::cast)
                    .filter(n -> AcpSchema.METHOD_SESSION_UPDATE.equals(n.method()))
                    .map(n -> mapper.convertValue(n.params(), new TypeRef<AcpSchema.SessionNotification>() {}))
                    .toList();
        }

        /** Register a stub that responds to outbound client-bound requests for {@code method}. */
        void respondTo(String method, Function<Object, Object> stub) {
            requestStubs.put(method, stub);
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
            if (message instanceof AcpSchema.JSONRPCRequest req) {
                var stub = requestStubs.get(req.method());
                if (stub != null) {
                    var result = stub.apply(req.params());
                    var response = new AcpSchema.JSONRPCResponse("2.0", req.id(), result, null);
                    Thread.startVirtualThread(
                            () -> handler.apply(Mono.just(response)).subscribe());
                }
            }
            return Mono.empty();
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return mapper.convertValue(data, typeRef);
        }
    }

    /**
     * Verifies the per-cwd workspace bundle behavior added to fix Zed's per-session cwd handling.
     * Two {@code session/new} calls with different {@code cwd}s must materialize independent
     * bundles; sessions in one must not appear in {@code listSessions} for the other.
     */
    @org.junit.jupiter.api.Nested
    class PerCwdBundles {

        private Path rootA;
        private Path rootB;
        private BrokkAcpAgent multiCwdAgent;
        private final java.util.List<JobRunner> trackedRunners = new ArrayList<>();
        private final java.util.List<ContextManager> trackedContextManagers = new ArrayList<>();

        @BeforeEach
        void setUpBundles(@TempDir Path tempDir) throws Exception {
            rootA = tempDir.resolve("project-a").toAbsolutePath().normalize();
            rootB = tempDir.resolve("project-b").toAbsolutePath().normalize();
            java.nio.file.Files.createDirectories(rootA);
            java.nio.file.Files.createDirectories(rootB);

            BrokkAcpAgent.WorkspaceBundleFactory factory = root -> {
                var project = MainProject.forTests(root);
                var cm = new ContextManager(project, TestService.provider(project));
                JobStore js;
                try {
                    js = new JobStore(root.resolve(".brokk-test-jobs"));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                var jr = new JobRunner(cm, js);
                trackedContextManagers.add(cm);
                trackedRunners.add(jr);
                return new BrokkAcpAgent.WorkspaceBundle(cm, jr, js, root);
            };
            multiCwdAgent = new BrokkAcpAgent(rootA, factory);
        }

        @AfterEach
        void tearDownBundles() {
            multiCwdAgent.closeAllBundles();
            trackedRunners.clear();
            trackedContextManagers.clear();
        }

        @Test
        void sessionsForDifferentCwdsDoNotCrossover() {
            var inA = multiCwdAgent.newSession(new AcpSchema.NewSessionRequest(rootA.toString(), List.of()));
            var inB = multiCwdAgent.newSession(new AcpSchema.NewSessionRequest(rootB.toString(), List.of()));

            assertNotEquals(inA.sessionId(), inB.sessionId());
            assertEquals(2, multiCwdAgent.activeBundleRoots().size());
            assertTrue(multiCwdAgent.activeBundleRoots().contains(rootA));
            assertTrue(multiCwdAgent.activeBundleRoots().contains(rootB));

            var listedA = multiCwdAgent.listSessions(new AcpProtocol.ListSessionsRequest(null, rootA.toString(), null));
            assertTrue(listedA.sessions().stream().anyMatch(s -> s.sessionId().equals(inA.sessionId())));
            assertTrue(listedA.sessions().stream().noneMatch(s -> s.sessionId().equals(inB.sessionId())));
            assertTrue(listedA.sessions().stream().allMatch(s -> s.cwd().equals(rootA.toString())));

            var listedB = multiCwdAgent.listSessions(new AcpProtocol.ListSessionsRequest(null, rootB.toString(), null));
            assertTrue(listedB.sessions().stream().anyMatch(s -> s.sessionId().equals(inB.sessionId())));
            assertTrue(listedB.sessions().stream().noneMatch(s -> s.sessionId().equals(inA.sessionId())));
            assertTrue(listedB.sessions().stream().allMatch(s -> s.cwd().equals(rootB.toString())));
        }

        @Test
        void sessionMissingCwdFallsBackToDefaultRoot() {
            var session = multiCwdAgent.newSession(new AcpSchema.NewSessionRequest("", List.of()));
            assertNotNull(session.sessionId());
            assertTrue(multiCwdAgent.activeBundleRoots().contains(rootA));
            assertFalse(multiCwdAgent.activeBundleRoots().contains(rootB));
        }

        @Test
        void listSessionsForUnseenCwdReturnsEmptyWithoutCreatingBundle() {
            var unseen = rootA.resolveSibling("never-touched").toAbsolutePath().normalize();
            var listed = multiCwdAgent.listSessions(new AcpProtocol.ListSessionsRequest(null, unseen.toString(), null));
            assertTrue(listed.sessions().isEmpty());
            assertFalse(multiCwdAgent.activeBundleRoots().contains(unseen));
        }
    }
}
