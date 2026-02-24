package ai.brokk.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.mcp.BrokkMcpStdioServer;
import ai.brokk.project.MainProject;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class McpServerCommandTest {

    @Test
    void toolDiscoveryList_containsExpectedTools() {
        Set<String> names = BrokkMcpStdioServer.toolDiscoveryList().stream()
                .map(McpSchema.Tool::name)
                .collect(Collectors.toSet());

        assertTrue(names.containsAll(Set.of(
                "scan",
                "find_symbols",
                "find_usages",
                "list_identifiers",
                "fetch_summary",
                "fetch_source",
                "code",
                "build",
                "merge")));
    }

    @Test
    void stubHandler_returnsNotImplemented() {
        McpSchema.CallToolResult result =
                BrokkMcpStdioServer.stubHandler(null, new McpSchema.CallToolRequest("unknown", Map.of()));

        assertEquals(false, result.isError());
        assertEquals(1, result.content().size());
        assertTrue(result.content().getFirst() instanceof McpSchema.TextContent text
                && "not implemented".equals(text.text()));
    }

    @Test
    void handleToolCall_unimplementedTool_returnsError() {
        McpSchema.CallToolResult result =
                BrokkMcpStdioServer.handleToolCall(null, new McpSchema.CallToolRequest("unknown_tool", Map.of()));

        assertTrue(result.isError());
        assertTrue(result.content().getFirst() instanceof McpSchema.TextContent text
                && text.text().contains("not yet implemented"));
    }

    @Test
    void handleToolCall_searchTools_delegation() throws Exception {
        Path tempDir = Files.createTempDirectory("mcp-test");
        // Ensure a real Java analyzer is used by creating a file with recognized extension
        Files.createDirectories(tempDir.resolve("src/main/java/ai/brokk/cli"));
        Files.writeString(
                tempDir.resolve("src/main/java/ai/brokk/cli/BrokkCli.java"),
                "package ai.brokk.cli; public class BrokkCli { public static void main(String[] args) {} }");

        try (var project = new MainProject(tempDir)) {
            ContextManager cm = new ContextManager(project);
            cm.createHeadless(ai.brokk.agents.BuildAgent.BuildDetails.EMPTY, false, new CliConsole());

            // Wait for analyzer to pick up the file and classes
            cm.getAnalyzerWrapper().requestRebuild();
            cm.liveContext().awaitContentsAreComputed(java.time.Duration.ofSeconds(10));

            // Test find_symbols
            var symbolsResult = BrokkMcpStdioServer.handleToolCall(
                    cm,
                    new McpSchema.CallToolRequest(
                            "find_symbols", Map.of("patterns", List.of(".*"), "goal", "test symbols")));
            assertFalse(symbolsResult.isError());
            assertTrue(symbolsResult.content().getFirst() instanceof McpSchema.TextContent);

            // Test find_usages
            var usagesResult = BrokkMcpStdioServer.handleToolCall(
                    cm,
                    new McpSchema.CallToolRequest(
                            "find_usages", Map.of("targets", List.of("java.lang.Object"), "goal", "test usages")));
            assertFalse(usagesResult.isError());
            assertTrue(usagesResult.content().getFirst() instanceof McpSchema.TextContent);

            // Test list_identifiers
            var identifiersResult = BrokkMcpStdioServer.handleToolCall(
                    cm, new McpSchema.CallToolRequest("list_identifiers", Map.of("dir", ".", "goal", "test list")));
            assertFalse(identifiersResult.isError());
            assertTrue(identifiersResult.content().getFirst() instanceof McpSchema.TextContent);

            // Test fetch_summary (Class FQN)
            var summaryResult = BrokkMcpStdioServer.handleToolCall(
                    cm,
                    new McpSchema.CallToolRequest(
                            "fetch_summary", Map.of("targets", List.of("ai.brokk.cli.BrokkCli"))));
            assertFalse(summaryResult.isError());
            String summaryText =
                    ((McpSchema.TextContent) summaryResult.content().getFirst()).text();
            assertTrue(summaryText.contains("class BrokkCli"), "Summary should contain class definition");

            // Test fetch_source (Class FQN)
            var sourceResult = BrokkMcpStdioServer.handleToolCall(
                    cm,
                    new McpSchema.CallToolRequest("fetch_source", Map.of("targets", List.of("ai.brokk.cli.BrokkCli"))));
            assertFalse(sourceResult.isError());
            String sourceText = ((McpSchema.TextContent) sourceResult.content().getFirst()).text();
            assertTrue(sourceText.contains("public class BrokkCli"), "Source should contain full class code");

            // Test fetch_source (Method selector)
            var methodResult = BrokkMcpStdioServer.handleToolCall(
                    cm,
                    new McpSchema.CallToolRequest(
                            "fetch_source", Map.of("targets", List.of("ai.brokk.cli.BrokkCli.main"))));
            assertFalse(methodResult.isError());
            String methodText = ((McpSchema.TextContent) methodResult.content().getFirst()).text();
            assertTrue(methodText.contains("public static void main"), "Source should contain method code");
        }
    }
}
