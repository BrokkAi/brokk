package ai.brokk.mcp;

import ai.brokk.ContextManager;
import ai.brokk.MutedConsoleIO;
import ai.brokk.agents.ContextAgent;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments.SummaryFragment;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class BrokkMcpStdioServer {
    private static final Logger logger = LogManager.getLogger(BrokkMcpStdioServer.class);

    public static int run(ContextManager cm) {
        var stdinClosed = new CountDownLatch(1);
        var originalIn = System.in;
        System.setIn(new EofNotifyingInputStream(originalIn, stdinClosed));

        var mapper = McpJsonDefaults.getMapper();
        var transport = new StdioServerTransportProvider(mapper);
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("Brokk", ai.brokk.BuildInfo.version)
                .tools(toolSpecifications(cm))
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully, "BrokkMcpServerShutdown"));

        try {
            // Block until the transport reaches EOF on stdin.
            stdinClosed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        } finally {
            // Best-effort cleanup (do not write to stdout).
            server.closeGracefully();
            System.setIn(originalIn);
        }

        return 0;
    }

    private static final class EofNotifyingInputStream extends FilterInputStream {
        private final CountDownLatch eofLatch;

        private EofNotifyingInputStream(InputStream in, CountDownLatch eofLatch) {
            super(in);
            this.eofLatch = eofLatch;
        }

        @Override
        public int read() throws IOException {
            int v = super.read();
            if (v == -1) {
                eofLatch.countDown();
            }
            return v;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n == -1) {
                eofLatch.countDown();
            }
            return n;
        }
    }

    public static List<SyncToolSpecification> toolSpecifications(ContextManager cm) {
        return toolDiscoveryList().stream()
                .map(tool -> SyncToolSpecification.builder()
                        .tool(tool)
                        .callHandler((exchange, request) -> handleToolCall(cm, request))
                        .build())
                .toList();
    }

    public static List<McpSchema.Tool> toolDiscoveryList() {
        return List.of(
                stubTool(
                        "scan",
                        "Agentic scan for relevant files and classes",
                        Map.of(
                                "goal", Map.of("type", "string"),
                                "files", Map.of("type", "array", "items", Map.of("type", "string")),
                                "include_tests", Map.of("type", "boolean")),
                        List.of("goal")),
                stubTool(
                        "find_symbols",
                        "Symbol search using regex patterns",
                        Map.of(
                                "patterns", Map.of("type", "array", "items", Map.of("type", "string")),
                                "goal", Map.of("type", "string"),
                                "include_tests", Map.of("type", "boolean")),
                        List.of("patterns", "goal")),
                stubTool(
                        "find_usages",
                        "Find where symbols are used",
                        Map.of(
                                "targets", Map.of("type", "array", "items", Map.of("type", "string")),
                                "goal", Map.of("type", "string"),
                                "include_tests", Map.of("type", "boolean")),
                        List.of("targets", "goal")),
                stubTool(
                        "list_identifiers",
                        "List identifiers in a directory",
                        Map.of("dir", Map.of("type", "string"), "goal", Map.of("type", "string")),
                        List.of("dir")),
                stubTool(
                        "fetch_summary",
                        "Get declarations for classes or files",
                        Map.of("targets", Map.of("type", "array", "items", Map.of("type", "string"))),
                        List.of("targets")),
                stubTool(
                        "fetch_source",
                        "Get full source code for classes or methods",
                        Map.of("targets", Map.of("type", "array", "items", Map.of("type", "string"))),
                        List.of("targets")),
                stubTool(
                        "code",
                        "Implement changes described in a goal",
                        Map.of(
                                "goal", Map.of("type", "string"),
                                "files", Map.of("type", "array", "items", Map.of("type", "string")),
                                "autocommit", Map.of("type", "boolean")),
                        List.of("goal")),
                stubTool("build", "Run build verification", Map.of(), List.of()),
                stubTool("merge", "Resolve merge conflicts", Map.of(), List.of()));
    }

    public static McpSchema.Tool stubTool(
            String name, String description, Map<String, Object> properties, List<String> required) {
        return McpSchema.Tool.builder()
                .name(name)
                .title(name)
                .description(description)
                .inputSchema(new McpSchema.JsonSchema("object", properties, required, false, null, null))
                .build();
    }

    public static McpSchema.CallToolResult handleToolCall(
            @Nullable ContextManager cm, McpSchema.CallToolRequest request) {
        try {
            return switch (request.name()) {
                case "scan" -> {
                    if (cm == null) {
                        yield McpSchema.CallToolResult.builder()
                                .addTextContent("Internal error: tool 'scan' requires a ContextManager")
                                .isError(true)
                                .build();
                    }
                    yield handleScan(cm, request);
                }
                default ->
                    McpSchema.CallToolResult.builder()
                            .addTextContent("Tool '" + request.name() + "' is not yet implemented")
                            .isError(true)
                            .build();
            };
        } catch (Exception e) {
            logger.error("Error executing MCP tool {}", request.name(), e);
            String msg = (e.getMessage() == null || e.getMessage().isBlank())
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Internal error: " + msg)
                    .isError(true)
                    .build();
        }
    }

    private static McpSchema.CallToolResult handleScan(ContextManager cm, McpSchema.CallToolRequest request)
            throws InterruptedException {
        var args = request.arguments();
        String goal = (String) args.get("goal");
        if (goal == null) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Missing required argument 'goal'")
                    .isError(true)
                    .build();
        }
        boolean includeTests = Boolean.TRUE.equals(args.get("include_tests"));

        var scanModel = cm.getService().getScanModel();
        var agent = new ContextAgent(cm, scanModel, goal, new MutedConsoleIO(cm.getIo()));
        var recommendations = agent.getRecommendations(cm.liveContext());

        if (!recommendations.success()) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Scan failed to complete.")
                    .isError(true)
                    .build();
        }

        var fragments = recommendations.fragments();
        if (fragments.isEmpty()) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("No relevant context found for the provided goal.")
                    .build();
        }

        var sb = new StringBuilder();
        sb.append("# Recommended Context\n\n");

        var st = fragments.stream();
        if (!includeTests) {
            st = st.filter(f -> f.sourceFiles().join().stream()
                    .noneMatch(pf -> ContextManager.isTestFile(pf, cm.getAnalyzerUninterrupted())));
        }

        st.flatMap(f -> toSummaryFragments(cm, f).stream()).forEach(f -> {
            sb.append("## ").append(f.description().join()).append(":\n");
            sb.append(f.text().join()).append("\n\n");
        });

        cm.pushContext(ctx -> ctx.addFragments(fragments));

        return McpSchema.CallToolResult.builder()
                .addTextContent(sb.toString().strip())
                .build();
    }

    public static McpSchema.CallToolResult stubHandler(
            @Nullable McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        return McpSchema.CallToolResult.builder()
                .addTextContent("not implemented")
                .isError(false)
                .build();
    }

    private static List<SummaryFragment> toSummaryFragments(ContextManager cm, ContextFragment fragment) {
        var results = new ArrayList<SummaryFragment>();

        var files = fragment.sourceFiles().join();
        for (var file : files) {
            results.add(new SummaryFragment(cm, file.toString(), ContextFragment.SummaryType.FILE_SKELETONS));
        }

        var sources = fragment.sources().join();
        for (var codeUnit : sources) {
            if (codeUnit.isClass()) {
                results.add(new SummaryFragment(cm, codeUnit.fqName(), ContextFragment.SummaryType.CODEUNIT_SKELETON));
            }
        }

        return results;
    }
}
