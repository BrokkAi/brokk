package ai.brokk.mcpserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.DisabledAnalyzer;
import ai.brokk.project.CoreProject;
import ai.brokk.tools.SearchTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
                "getFileSummaries",
                "getClassSources",
                "getMethodSources",
                "getClassSkeletons",
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
                "xmlSelect");

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

        var request = new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("getActiveWorkspace", Map.of());
        var result = activeTool.callHandler().apply(null, request);

        assertNotNull(result);
        assertFalse(result.content().isEmpty());
    }
}
