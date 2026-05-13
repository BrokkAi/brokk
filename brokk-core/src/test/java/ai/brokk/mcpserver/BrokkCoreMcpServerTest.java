package ai.brokk.mcpserver;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.DisabledAnalyzer;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.project.CoreProject;
import ai.brokk.project.ICoreProject;
import ai.brokk.tools.SearchTools;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrokkCoreMcpServerTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private CoreProject project;
    private BrokkCoreMcpServer server;

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = tempDir.resolve("repo");
        Files.createDirectories(projectRoot);
        try (Git git = Git.init().setDirectory(projectRoot.toFile()).call()) {
            Files.writeString(projectRoot.resolve("README.md"), "# Test");
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setMessage("init")
                    .setAuthor("Test", "test@test.com")
                    .setSign(false)
                    .call();
        }

        project = new CoreProject(projectRoot);
        var analyzer = new DisabledAnalyzer(project);
        var intel = new StandaloneCodeIntelligence(project, analyzer);
        var searchTools = new SearchTools(intel);
        server = new BrokkCoreMcpServer(project, intel, searchTools);
    }

    @AfterEach
    void tearDown() {
        if (project != null) {
            project.close();
        }
    }

    // -- Tool registration tests --

    @Test
    void registersAllExpectedTools() {
        var specs = server.toolSpecifications();
        assertNotNull(specs);

        var toolNames = specs.stream().map(s -> s.tool().name()).collect(Collectors.toSet());

        var expectedTools = Set.of(
                "activateWorkspace",
                "getActiveWorkspace",
                "searchSymbols",
                "scanUsages",
                "getSummaries",
                "getClassSources",
                "getMethodSources",
                "getSymbolLocations",
                "findFilenames",
                "findFilesContaining",
                "searchFileContents",
                "searchGitCommitMessages",
                "getGitLog",
                "getFileContents",
                "listFiles",
                "skimFiles",
                "jq",
                "xmlSkim",
                "xmlSelect",
                "computeCyclomaticComplexity",
                "computeCognitiveComplexity",
                "reportCommentDensityForCodeUnit",
                "reportCommentDensityForFiles",
                "reportLongMethodAndGodObjectSmells",
                "reportExceptionHandlingSmells",
                "reportStructuralCloneSmells",
                "reportTestAssertionSmells",
                "reportDeadCodeAndUnusedAbstractionSmells");

        for (var expected : expectedTools) {
            assertTrue(toolNames.contains(expected), "Missing tool: " + expected);
        }
        assertEquals(expectedTools.size(), specs.size(), "Unexpected number of tools registered");
    }

    @Test
    void toolSpecificationsHaveDescriptions() {
        var specs = server.toolSpecifications();
        for (var spec : specs) {
            assertNotNull(
                    spec.tool().description(),
                    "Tool %s missing description".formatted(spec.tool().name()));
            assertFalse(
                    spec.tool().description().isBlank(),
                    "Tool %s has blank description".formatted(spec.tool().name()));
        }
    }

    @Test
    void toolSpecificationsHaveInputSchemas() {
        var specs = server.toolSpecifications();
        for (var spec : specs) {
            assertNotNull(
                    spec.tool().inputSchema(),
                    "Tool %s missing input schema".formatted(spec.tool().name()));
        }
    }

    // -- resolveProjectRoot tests --

    @Test
    void resolveProjectRootFindsGitRoot() {
        var resolved = BrokkCoreMcpServer.resolveProjectRoot(projectRoot);
        assertEquals(projectRoot.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void resolveProjectRootFromSubdirectory() throws Exception {
        var subDir = projectRoot.resolve("src/main");
        Files.createDirectories(subDir);

        var resolved = BrokkCoreMcpServer.resolveProjectRoot(subDir);
        assertEquals(projectRoot.toAbsolutePath().normalize(), resolved, "Should walk up to find .git directory");
    }

    @Test
    void resolveProjectRootFallsBackToInput() {
        var fsRoot = Path.of("").toAbsolutePath().getRoot();
        assertNotNull(fsRoot);

        var noGitPath = fsRoot.resolve("brokk-core-mcp-no-git-" + System.nanoTime());
        var resolved = BrokkCoreMcpServer.resolveProjectRoot(noGitPath);
        assertEquals(noGitPath.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void resolveProjectRootIgnoresInvalidGitParent() throws Exception {
        var parent = tempDir.resolve("invalid-git-parent");
        Files.createDirectories(parent.resolve(".git"));
        var noGitDir = parent.resolve("no-git");
        Files.createDirectories(noGitDir);

        var resolved = BrokkCoreMcpServer.resolveProjectRoot(noGitDir);
        assertEquals(noGitDir.toAbsolutePath().normalize(), resolved);
    }

    // -- getActiveWorkspace tool test --

    @Test
    void getActiveWorkspaceReturnsCurrentRoot() {
        var specs = server.toolSpecifications();
        var activeTool = specs.stream()
                .filter(s -> "getActiveWorkspace".equals(s.tool().name()))
                .findFirst()
                .orElseThrow();

        var request = new McpSchema.CallToolRequest("getActiveWorkspace", Map.of());
        var result = activeTool.callHandler().apply(null, request);

        assertNotNull(result);
        assertFalse(result.content().isEmpty());
    }

    @Test
    void toolCallsDoNotLogToMcpHistoryByDefault() {
        var specs = server.toolSpecifications();
        var activeTool = specs.stream()
                .filter(s -> "getActiveWorkspace".equals(s.tool().name()))
                .findFirst()
                .orElseThrow();

        var request = new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("getActiveWorkspace", Map.of());
        var result = activeTool.callHandler().apply(null, request);

        assertNotNull(result);
        assertFalse(Files.exists(projectRoot.resolve(".brokk").resolve("mcp-history")));
    }

    @Test
    void analyzerBuildFailureIsReturnedFromAnalyzerBackedTools() throws Exception {
        server = newServerAwaitingAnalyzer();

        var thread = server.startAnalyzerBuild(failingLanguage());
        thread.join();

        var result = callTool("searchSymbols", Map.of("patterns", List.of(".*"), "includeTests", false, "limit", 10));

        assertTrue(result.isError() != null && result.isError());
        assertTrue(textContent(result).contains("Analyzer build failed"));
    }

    @Test
    void analyzerBuildFailureDoesNotPreventWorkspaceActivation() throws Exception {
        server = newServerAwaitingAnalyzer();

        var thread = server.startAnalyzerBuild(failingLanguage());
        thread.join();

        var newRoot = tempDir.resolve("other-repo");
        Files.createDirectories(newRoot);
        try (Git git = Git.init().setDirectory(newRoot.toFile()).call()) {
            Files.writeString(newRoot.resolve("README.md"), "# Other");
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setMessage("init")
                    .setAuthor("Test", "test@test.com")
                    .setSign(false)
                    .call();
        }

        var result = callTool("activateWorkspace", Map.of("workspacePath", newRoot.toString()));

        assertFalse(result.isError() != null && result.isError());
        assertEquals(
                newRoot,
                BrokkCoreMcpServer.resolveProjectRoot(Path.of(textContent(callTool("getActiveWorkspace", Map.of())))));

        var searchResult =
                callTool("searchSymbols", Map.of("patterns", List.of(".*"), "includeTests", false, "limit", 10));
        assertFalse(searchResult.isError() != null && searchResult.isError());
        assertFalse(textContent(searchResult).contains("Analyzer build failed"));
    }

    @Test
    void workspaceActivationSupersedesRunningStartupAnalyzerBuild() throws Exception {
        server = newServerAwaitingAnalyzer();
        var startupBuildStarted = new CountDownLatch(1);
        var startupThread = server.startAnalyzerBuild(interruptibleLanguage(startupBuildStarted));
        assertTrue(startupBuildStarted.await(5, TimeUnit.SECONDS));

        var newRoot = tempDir.resolve("other-repo");
        Files.createDirectories(newRoot);
        try (Git git = Git.init().setDirectory(newRoot.toFile()).call()) {
            Files.writeString(newRoot.resolve("README.md"), "# Other");
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setMessage("init")
                    .setAuthor("Test", "test@test.com")
                    .setSign(false)
                    .call();
        }

        var activationResult = new AtomicReference<McpSchema.CallToolResult>();
        var activationThread = Thread.ofVirtual()
                .start(() -> activationResult.set(
                        callTool("activateWorkspace", Map.of("workspacePath", newRoot.toString()))));

        activationThread.join(5000);
        startupThread.join(5000);

        assertFalse(activationThread.isAlive());
        assertFalse(startupThread.isAlive());
        var result = requireNonNull(activationResult.get());
        assertFalse(result.isError() != null && result.isError());
        assertEquals(
                newRoot,
                BrokkCoreMcpServer.resolveProjectRoot(Path.of(textContent(callTool("getActiveWorkspace", Map.of())))));
    }

    @Test
    void sameWorkspaceActivationRebuildsAfterCancellingStartupAnalyzerBuild() throws Exception {
        Files.createDirectories(projectRoot.resolve("src/main/java/example"));
        Files.writeString(
                projectRoot.resolve("src/main/java/example/SameWorkspaceMarker.java"),
                """
                package example;

                class SameWorkspaceMarker {
                }
                """);
        server = newServerAwaitingAnalyzer();
        var startupBuildStarted = new CountDownLatch(1);
        var startupThread = server.startAnalyzerBuild(interruptibleLanguage(startupBuildStarted));
        assertTrue(startupBuildStarted.await(5, TimeUnit.SECONDS));

        var result = callTool("activateWorkspace", Map.of("workspacePath", projectRoot.toString()));
        startupThread.join(5000);

        assertFalse(result.isError() != null && result.isError());
        assertFalse(startupThread.isAlive());

        var searchResult = callTool(
                "searchSymbols",
                Map.of("patterns", List.of(".*SameWorkspaceMarker.*"), "includeTests", false, "limit", 10));
        assertFalse(searchResult.isError() != null && searchResult.isError());
        assertTrue(textContent(searchResult).contains("SameWorkspaceMarker"));
    }

    @Test
    void failedActivationAfterCancellingStartupAnalyzerCanBeRetried() throws Exception {
        Files.createDirectories(projectRoot.resolve("src/main/java/example"));
        Files.writeString(
                projectRoot.resolve("src/main/java/example/RetryMarker.java"),
                """
                package example;

                class RetryMarker {
                }
                """);
        var failNextActivation = new AtomicBoolean(true);
        server = new FlakyActivationServer(project, failNextActivation);
        var startupBuildStarted = new CountDownLatch(1);
        var startupThread = server.startAnalyzerBuild(interruptibleLanguage(startupBuildStarted));
        assertTrue(startupBuildStarted.await(5, TimeUnit.SECONDS));

        var failedResult = callTool("activateWorkspace", Map.of("workspacePath", projectRoot.toString()));
        startupThread.join(5000);

        assertTrue(failedResult.isError() != null && failedResult.isError());
        assertFalse(startupThread.isAlive());

        var retryResult = callTool("activateWorkspace", Map.of("workspacePath", projectRoot.toString()));
        assertFalse(retryResult.isError() != null && retryResult.isError());

        var searchResult = callTool(
                "searchSymbols", Map.of("patterns", List.of(".*RetryMarker.*"), "includeTests", false, "limit", 10));
        assertFalse(searchResult.isError() != null && searchResult.isError());
        assertTrue(textContent(searchResult).contains("RetryMarker"));
    }

    private BrokkCoreMcpServer newServerAwaitingAnalyzer() {
        var analyzer = new DisabledAnalyzer(project);
        var intel = new StandaloneCodeIntelligence(project, analyzer);
        var searchTools = new SearchTools(intel);
        return new BrokkCoreMcpServer(project, intel, searchTools, null, false);
    }

    private static class FlakyActivationServer extends BrokkCoreMcpServer {
        private final AtomicBoolean failNextActivation;

        FlakyActivationServer(CoreProject project, AtomicBoolean failNextActivation) {
            super(
                    project,
                    new StandaloneCodeIntelligence(project, new DisabledAnalyzer(project)),
                    new SearchTools(new StandaloneCodeIntelligence(project, new DisabledAnalyzer(project))),
                    null,
                    false);
            this.failNextActivation = failNextActivation;
        }

        @Override
        void activateWorkspaceLocked(Path newRoot) {
            if (failNextActivation.getAndSet(false)) {
                throw new IllegalStateException("activation failed");
            }
            super.activateWorkspaceLocked(newRoot);
        }
    }

    @Test
    void toolCallsAreLoggedToMcpHistoryWhenEnabled() throws Exception {
        var analyzer = new DisabledAnalyzer(project);
        var intel = new StandaloneCodeIntelligence(project, analyzer);
        var searchTools = new SearchTools(intel);
        Path historyRoot = tempDir.resolve("mcp-history");
        server = new BrokkCoreMcpServer(project, intel, searchTools, historyRoot);

        var specs = server.toolSpecifications();
        var activeTool = specs.stream()
                .filter(s -> "getActiveWorkspace".equals(s.tool().name()))
                .findFirst()
                .orElseThrow();

        var request = new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("getActiveWorkspace", Map.of());
        var result = activeTool.callHandler().apply(null, request);

        assertNotNull(result);

        assertTrue(Files.isDirectory(historyRoot), "Expected mcp-history directory to exist");
        assertFalse(Files.exists(projectRoot.resolve(".brokk")), "Expected history to stay outside the workdir");

        Path logFile;
        try (Stream<Path> files = Files.walk(historyRoot)) {
            logFile = files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }

        String logText = Files.readString(logFile);
        assertTrue(logText.contains("# Request"), "Expected request section in history log");
        assertTrue(logText.contains("# Response"), "Expected response section in history log");
        assertTrue(logText.contains("getActiveWorkspace"), "Expected tool name in history log");
        assertTrue(logText.contains(projectRoot.toString()), "Expected workspace path in history log response");
    }

    // -- Code quality tool execution tests --

    private McpSchema.CallToolResult callTool(String name, Map<String, Object> args) {
        var specs = server.toolSpecifications();
        var tool = specs.stream()
                .filter(s -> name.equals(s.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found: " + name));
        var request = new McpSchema.CallToolRequest(name, args);
        return tool.callHandler().apply(null, request);
    }

    private static Language failingLanguage() {
        return new Language() {
            @Override
            public Set<String> getExtensions() {
                return Set.of("fail");
            }

            @Override
            public String name() {
                return "Failing";
            }

            @Override
            public String internalName() {
                return "FAILING";
            }

            @Override
            public IAnalyzer createAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
                throw new IllegalStateException("boom");
            }

            @Override
            public IAnalyzer loadAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
                throw new IllegalStateException("boom");
            }
        };
    }

    private static Language interruptibleLanguage(CountDownLatch startupBuildStarted) {
        return new Language() {
            @Override
            public Set<String> getExtensions() {
                return Set.of("interrupt");
            }

            @Override
            public String name() {
                return "Interruptible";
            }

            @Override
            public String internalName() {
                return "INTERRUPTIBLE";
            }

            @Override
            public IAnalyzer createAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
                startupBuildStarted.countDown();
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return new DisabledAnalyzer(project);
            }

            @Override
            public IAnalyzer loadAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static String textContent(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .collect(Collectors.joining("\n"));
    }

    @Test
    void computeCyclomaticComplexityRunsWithoutError() {
        var result =
                callTool("computeCyclomaticComplexity", Map.of("filePaths", List.of("README.md"), "threshold", 10));
        assertNotNull(result);
        assertFalse(result.isError() != null && result.isError());
        assertFalse(result.content().isEmpty());
    }

    @Test
    void computeCognitiveComplexityRunsWithoutError() {
        var result = callTool("computeCognitiveComplexity", Map.of("filePaths", List.of("README.md"), "threshold", 15));
        assertNotNull(result);
        assertFalse(result.isError() != null && result.isError());
        assertFalse(result.content().isEmpty());
    }

    @Test
    void reportLongMethodAndGodObjectSmellsRunsWithoutError() {
        var result = callTool("reportLongMethodAndGodObjectSmells", Map.of("filePaths", List.of("README.md")));
        assertNotNull(result);
        assertFalse(result.isError() != null && result.isError());
        assertFalse(result.content().isEmpty());
    }

    @Test
    void reportCommentDensityForCodeUnitRunsWithoutError() {
        var result = callTool(
                "reportCommentDensityForCodeUnit", Map.of("fqName", "com.example.NonExistent", "maxLines", 120));
        assertNotNull(result);
        assertFalse(result.isError() != null && result.isError());
        assertFalse(result.content().isEmpty());
    }

    @Test
    void reportCommentDensityForFilesRunsWithoutError() {
        var result = callTool(
                "reportCommentDensityForFiles",
                Map.of(
                        "filePaths", List.of("README.md"),
                        "maxTopLevelRows", 60,
                        "maxFiles", 25));
        assertNotNull(result);
        assertFalse(result.isError() != null && result.isError());
        assertFalse(result.content().isEmpty());
    }

    @Test
    void reportExceptionHandlingSmellsRunsWithoutError() {
        var result = callTool("reportExceptionHandlingSmells", Map.of("filePaths", List.of("README.md")));
        assertNotNull(result);
        assertFalse(result.isError() != null && result.isError());
        assertFalse(result.content().isEmpty());
    }

    @Test
    void reportStructuralCloneSmellsRunsWithoutError() {
        var result = callTool("reportStructuralCloneSmells", Map.of("filePaths", List.of("README.md")));
        assertNotNull(result);
        assertFalse(result.isError() != null && result.isError());
        assertFalse(result.content().isEmpty());
    }

    @Test
    void reportTestAssertionSmellsRunsWithoutError() {
        var result = callTool("reportTestAssertionSmells", Map.of("filePaths", List.of("README.md")));
        assertNotNull(result);
        assertFalse(result.isError() != null && result.isError());
        assertFalse(result.content().isEmpty());
    }

    @Test
    void reportDeadCodeAndUnusedAbstractionSmellsAcceptsMissingFqNames() {
        var result = callTool("reportDeadCodeAndUnusedAbstractionSmells", Map.of("filePaths", List.of("README.md")));
        assertNotNull(result);
        assertFalse(result.isError() != null && result.isError());
        assertFalse(result.content().isEmpty());
    }

    @Test
    void reportDeadCodeReportsUnusedSymbolWithEvidence() throws Exception {
        rebuildServerWithJavaSources(Map.of(
                "src/main/java/com/example/Sample.java",
                """
                package com.example;
                public class Sample {
                    public int used(int value) {
                        return value + 1;
                    }

                    private int unusedHelper(int value) {
                        return value * 2;
                    }
                }
                """
                        .stripIndent()));

        var result = callTool(
                "reportDeadCodeAndUnusedAbstractionSmells",
                Map.of(
                        "filePaths",
                        List.of("src/main/java/com/example/Sample.java"),
                        "fqNames",
                        List.of("com.example.Sample.unusedHelper")));
        assertFalse(result.isError() != null && result.isError());
        String text = textOf(result);
        assertTrue(text.contains("com.example.Sample.unusedHelper"), text);
        assertTrue(text.contains("no non-self usages found"), text);
        assertTrue(text.contains("may be generated residue"), text);
    }

    @Test
    void reportDeadCodeFlagsOneCallAbstraction() throws Exception {
        rebuildServerWithJavaSources(Map.of(
                "src/main/java/com/example/Target.java",
                """
                package com.example;
                public class Target {
                    public int oneCallWrapper(int value) {
                        return value + 1;
                    }
                }
                """
                        .stripIndent(),
                "src/main/java/com/example/Caller.java",
                """
                package com.example;
                public class Caller {
                    public int call(Target target) {
                        return target.oneCallWrapper(41);
                    }
                }
                """
                        .stripIndent()));

        var result = callTool(
                "reportDeadCodeAndUnusedAbstractionSmells",
                Map.of(
                        "filePaths",
                        List.of("src/main/java/com/example/Target.java"),
                        "fqNames",
                        List.of("com.example.Target.oneCallWrapper")));
        assertFalse(result.isError() != null && result.isError());
        String text = textOf(result);
        assertTrue(text.contains("com.example.Target.oneCallWrapper"), text);
        assertTrue(text.contains("only usage"), text);
        assertTrue(text.contains("low-value abstraction"), text);
    }

    @Test
    void reportDeadCodeSuppressesSymbolsWithMultipleCallers() throws Exception {
        rebuildServerWithJavaSources(Map.of(
                "src/main/java/com/example/Target.java",
                """
                package com.example;
                public class Target {
                    public int usedByMany(int value) {
                        return value + 1;
                    }
                }
                """
                        .stripIndent(),
                "src/main/java/com/example/First.java",
                """
                package com.example;
                public class First {
                    public int call(Target target) {
                        return target.usedByMany(1);
                    }
                }
                """
                        .stripIndent(),
                "src/main/java/com/example/Second.java",
                """
                package com.example;
                public class Second {
                    public int call(Target target) {
                        return target.usedByMany(2);
                    }
                }
                """
                        .stripIndent()));

        var result = callTool(
                "reportDeadCodeAndUnusedAbstractionSmells",
                Map.of(
                        "filePaths",
                        List.of("src/main/java/com/example/Target.java"),
                        "fqNames",
                        List.of("com.example.Target.usedByMany")));
        assertFalse(result.isError() != null && result.isError());
        String text = textOf(result);
        assertTrue(text.contains("No dead code or unused abstraction smells met minScore"), text);
        assertFalse(text.contains("| `com.example.Target.usedByMany` |"), text);
    }

    @Test
    void reportDeadCodeDiscoversCandidatesWhenFqNamesEmpty() throws Exception {
        rebuildServerWithJavaSources(Map.of(
                "src/main/java/com/example/Discovery.java",
                """
                package com.example;
                public class Discovery {
                    private int unusedDeclaration(int value) {
                        return value * 3;
                    }
                }
                """
                        .stripIndent()));

        var result = callTool(
                "reportDeadCodeAndUnusedAbstractionSmells",
                Map.of(
                        "filePaths", List.of("src/main/java/com/example/Discovery.java"),
                        "fqNames", List.of()));
        assertFalse(result.isError() != null && result.isError());
        String text = textOf(result);
        assertTrue(text.contains("unusedDeclaration"), text);
        assertTrue(text.contains("no non-self usages found"), text);
    }

    // -- Helpers --

    private String textOf(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .collect(Collectors.joining("\n"));
    }

    private void rebuildServerWithJavaSources(Map<String, String> sources) throws Exception {
        commitTrackedFiles(projectRoot, sources, Instant.parse("2025-01-01T00:00:00Z"), "Add Java sources");
        project.close();
        project = new CoreProject(projectRoot);
        IAnalyzer analyzer = Languages.JAVA.createAnalyzer(project);
        var intel = new StandaloneCodeIntelligence(project, analyzer);
        var searchTools = new SearchTools(intel);
        server = new BrokkCoreMcpServer(project, intel, searchTools);
    }

    private static void commitTrackedFiles(
            Path projectRoot, Map<String, String> filesByPath, Instant instant, String message) throws Exception {
        try (Git git = Git.open(projectRoot.toFile())) {
            var ident = new PersonIdent("Test User", "test@example.com", instant, ZoneId.of("UTC"));
            for (var entry : filesByPath.entrySet()) {
                Path file = projectRoot.resolve(entry.getKey());
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, entry.getValue());
                git.add().addFilepattern(entry.getKey().replace('\\', '/')).call();
            }
            git.commit()
                    .setMessage(message)
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .setSign(false)
                    .call();
        }
    }
}
