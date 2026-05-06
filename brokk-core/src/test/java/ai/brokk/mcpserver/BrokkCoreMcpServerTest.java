package ai.brokk.mcpserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.DisabledAnalyzer;
import ai.brokk.project.CoreProject;
import ai.brokk.tools.SearchTools;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
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
                "reportCommentDensityForCodeUnit",
                "reportCommentDensityForFiles",
                "reportExceptionHandlingSmells",
                "reportStructuralCloneSmells");

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
    void resolveProjectRootFallsBackToInput() throws Exception {
        var noGitDir = tempDir.resolve("no-git");
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

    @Test
    void computeCyclomaticComplexityRunsWithoutError() {
        var result =
                callTool("computeCyclomaticComplexity", Map.of("filePaths", List.of("README.md"), "threshold", 10));
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
}
