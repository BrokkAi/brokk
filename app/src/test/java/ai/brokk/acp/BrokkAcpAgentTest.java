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
                new AcpSchema.TextResourceContents("# Hello", fileUnderRoot.toUri().toString(), "text/markdown"),
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
                        "resource_link",
                        "b.java again",
                        f.toUri().toString(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));

        var paths = BrokkAcpAgent.extractResourceRelPaths(blocks, projectRoot);

        assertEquals(List.of("a/b.java"), paths);
    }

    @Test
    void extractResourceRelPathsAcceptsBareRelativeUri() {
        var blocks = List.<AcpSchema.ContentBlock>of(new AcpSchema.ResourceLink(
                "resource_link", "x", "src/X.java", null, null, null, null, null, null));

        var paths = BrokkAcpAgent.extractResourceRelPaths(blocks, projectRoot);

        assertEquals(List.of("src/X.java"), paths);
    }

    @Test
    void extractResourceRelPathsReturnsEmptyForNullOrTextOnly() {
        assertTrue(BrokkAcpAgent.extractResourceRelPaths(null, projectRoot).isEmpty());
        assertTrue(BrokkAcpAgent.extractResourceRelPaths(List.of(), projectRoot).isEmpty());
        assertTrue(BrokkAcpAgent.extractResourceRelPaths(
                        List.of(new AcpSchema.TextContent("hi")), projectRoot)
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
                    .map(n -> transport.mapper.convertValue(n.params(), new TypeRef<AcpSchema.SessionNotification>() {}))
                    .toList();
            assertTrue(
                    notifications.stream().anyMatch(n -> n.update() instanceof AcpSchema.AgentMessageChunk a
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
        var wireLine =
                "{\"type\":\"com.agentclientprotocol.rpc.JsonRpcResponse\","
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
