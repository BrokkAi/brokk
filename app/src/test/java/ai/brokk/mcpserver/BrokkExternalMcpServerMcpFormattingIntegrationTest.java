package ai.brokk.mcpserver;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.MutedConsoleIO;
import ai.brokk.tools.SearchTools;
import ai.brokk.tools.ToolRegistry;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BrokkExternalMcpServerMcpFormattingIntegrationTest {

    @Test
    void mcpGetClassSources_returnsRealFileLineNumbers() throws Exception {
        try (var cm = contextManagerWithSampleJava()) {
            String output = invokeTool(cm, "getClassSources", Map.of("classNames", List.of("com.example.Foo")));
            assertTrue(output.matches("(?s).*```.*Foo\\.java\\R.*"), output);
            assertTrue(output.contains("3: class Foo {"), output);
            assertTrue(output.contains("4:     int inc(int x) {"), output);
        }
    }

    @Test
    void mcpGetClassSources_acceptsUniqueNonFqName() throws Exception {
        try (var cm = contextManagerWithSampleJava()) {
            String output = invokeTool(cm, "getClassSources", Map.of("classNames", List.of("Foo")));
            assertTrue(output.matches("(?s).*```.*Foo\\.java\\R.*"), output);
            assertTrue(output.contains("3: class Foo {"), output);
            assertTrue(output.contains("4:     int inc(int x) {"), output);
        }
    }

    @Test
    void mcpGetMethodSources_returnsRealFileLineNumbers() throws Exception {
        try (var cm = contextManagerWithSampleJava()) {
            String output = invokeTool(cm, "getMethodSources", Map.of("methodNames", List.of("com.example.Foo.inc")));
            assertTrue(output.matches("(?s).*```.*Foo\\.java\\R.*"), output);
            assertTrue(output.contains("4:     int inc(int x) {"), output);
            assertTrue(output.contains("5:         return x + 1;"), output);
        }
    }

    @Test
    void mcpGetMethodSources_acceptsUniqueNonFqName() throws Exception {
        try (var cm = contextManagerWithSampleJava()) {
            String output = invokeTool(cm, "getMethodSources", Map.of("methodNames", List.of("inc")));
            assertTrue(output.matches("(?s).*```.*Foo\\.java\\R.*"), output);
            assertTrue(output.contains("4:     int inc(int x) {"), output);
            assertTrue(output.contains("5:         return x + 1;"), output);
        }
    }

    @Test
    void mcpScanUsages_returnsNumberedExamples() throws Exception {
        try (var cm = contextManagerWithSampleJava()) {
            String output = invokeTool(
                    cm, "scanUsages", Map.of("symbols", List.of("com.example.Foo.inc"), "includeTests", false));
            assertTrue(output.contains("# Usages of com.example.Foo.inc"), output);
            assertTrue(output.matches("(?s).*`com\\.example\\.Caller\\.baz` \\([^)]*:11\\).*"), output);
            assertTrue(output.matches("(?s).*```.*Foo\\.java\\R.*"), output);
            assertTrue(output.contains("10:     int baz() {"), output);
            assertTrue(output.contains("11:         return new Foo().inc(1);"), output);
            assertFalse(output.contains("1: 10:"), "Output should not have double-prefixed line numbers");
        }
    }

    @Test
    void mcpGetFileContents_mixedSuccess_preservesRawToolOutput() throws Exception {
        try (var cm = contextManagerWithSampleJava()) {
            String output = invokeTool(
                    cm,
                    "getFileContents",
                    Map.of(
                            "filenames",
                            List.of("src/main/java/com/example/Foo.java", "src/main/java/com/example/Missing.java")));
            assertTrue(output.matches("(?s).*```.*Foo\\.java\\R.*"), output);
            assertTrue(output.contains("class Foo {"), output);
            assertFalse(output.contains("1: package com.example;"), output);
        }
    }

    @Test
    void mcpSearchFileContents_findsBroadRepoTermsInMarkdown() throws Exception {
        try (var cm = contextManagerWithLexicalFallbackProject()) {
            String symbolOutput = invokeTool(
                    cm,
                    "searchSymbols",
                    Map.of(
                            "patterns",
                            List.of(".*MCP.*", ".*ToolRegistry.*", ".*skill.*"),
                            "includeTests",
                            true,
                            "limit",
                            20));
            assertTrue(symbolOutput.contains("No definitions found"), symbolOutput);

            String output = invokeTool(
                    cm,
                    "searchFileContents",
                    Map.of(
                            "patterns",
                            List.of("MCP", "ToolRegistry", "skill"),
                            "filepath",
                            "**/*.md",
                            "searchType",
                            "all",
                            "caseInsensitive",
                            true,
                            "multiline",
                            false,
                            "contextLines",
                            0,
                            "maxFiles",
                            10));
            assertTrue(output.contains("README.md"), output);
            assertTrue(output.contains("MCP"), output);
            assertTrue(output.contains("ToolRegistry"), output);
            assertTrue(output.contains("skill"), output);
        }
    }

    private static ContextManager contextManagerWithSampleJava() throws Exception {
        Path tempDir = Files.createTempDirectory("mcp-formatting");
        Path source = tempDir.resolve("src/main/java/com/example/Foo.java");
        Files.createDirectories(source.getParent());
        Files.writeString(
                source,
                """
                package com.example;

                class Foo {
                    int inc(int x) {
                        return x + 1;
                    }
                }

                class Caller {
                    int baz() {
                        return new Foo().inc(1);
                    }
                }
                """);
        var project = new ai.brokk.project.MainProject(tempDir);
        var cm = new ContextManager(project);
        cm.createHeadless(true, new MutedConsoleIO(cm.getIo()));
        return cm;
    }

    private static ContextManager contextManagerWithLexicalFallbackProject() throws Exception {
        Path tempDir = Files.createTempDirectory("mcp-lexical");
        Path readme = tempDir.resolve("README.md");
        Files.writeString(
                readme,
                """
                MCP wiring notes
                ToolRegistry is responsible for exposing tools.
                This skill guide explains the fallback path.
                """);
        var project = new ai.brokk.project.MainProject(tempDir);
        var cm = new ContextManager(project);
        cm.createHeadless(true, new MutedConsoleIO(cm.getIo()));
        return cm;
    }

    private static String invokeTool(ContextManager cm, String toolName, Map<String, Object> args)
            throws InterruptedException {
        SearchTools searchTools = new SearchTools(cm);
        ToolRegistry registry = ToolRegistry.fromBase(ToolRegistry.empty())
                .register(searchTools)
                .build();
        List<McpServerFeatures.SyncToolSpecification> specs =
                BrokkExternalMcpServer.toolSpecificationsFrom(cm, registry, List.of(toolName));
        McpServerFeatures.SyncToolSpecification spec = specs.getFirst();

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name(toolName)
                .arguments(args)
                .build();

        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);
        return result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
    }
}
