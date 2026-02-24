package ai.brokk.mcpserver;

import ai.brokk.ContextManager;
import ai.brokk.MutedConsoleIO;
import ai.brokk.agents.ContextAgent;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments.SummaryFragment;
import ai.brokk.tools.SearchTools;
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
                case "scan" -> handleScan(requireContextManager(cm, "scan"), request);
                case "find_symbols" -> handleFindSymbols(requireContextManager(cm, "find_symbols"), request);
                case "find_usages" -> handleFindUsages(requireContextManager(cm, "find_usages"), request);
                case "list_identifiers" ->
                    handleListIdentifiers(requireContextManager(cm, "list_identifiers"), request);
                case "fetch_summary" -> handleFetchSummary(requireContextManager(cm, "fetch_summary"), request);
                case "fetch_source" -> handleFetchSource(requireContextManager(cm, "fetch_source"), request);
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

    private static McpSchema.CallToolResult handleFindSymbols(ContextManager cm, McpSchema.CallToolRequest request) {
        var args = request.arguments();
        @SuppressWarnings("unchecked")
        List<String> patterns = (List<String>) args.get("patterns");
        String goal = (String) args.getOrDefault("goal", "");
        boolean includeTests = Boolean.TRUE.equals(args.get("include_tests"));

        if (patterns == null || patterns.isEmpty()) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Missing required argument 'patterns'")
                    .isError(true)
                    .build();
        }

        String result = new SearchTools(cm).searchSymbols(patterns, goal, includeTests);
        return McpSchema.CallToolResult.builder().addTextContent(result).build();
    }

    private static McpSchema.CallToolResult handleFindUsages(ContextManager cm, McpSchema.CallToolRequest request) {
        var args = request.arguments();
        @SuppressWarnings("unchecked")
        List<String> targets = (List<String>) args.get("targets");
        String goal = (String) args.getOrDefault("goal", "");
        boolean includeTests = Boolean.TRUE.equals(args.get("include_tests"));

        if (targets == null || targets.isEmpty()) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Missing required argument 'targets'")
                    .isError(true)
                    .build();
        }

        String result = new SearchTools(cm).scanUsages(targets, goal, includeTests);
        return McpSchema.CallToolResult.builder().addTextContent(result).build();
    }

    private static McpSchema.CallToolResult handleListIdentifiers(
            ContextManager cm, McpSchema.CallToolRequest request) {
        var args = request.arguments();
        String dir = (String) args.get("dir");
        String goal = (String) args.getOrDefault("goal", "");

        if (dir == null) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Missing required argument 'dir'")
                    .isError(true)
                    .build();
        }

        String result = new SearchTools(cm).skimDirectory(dir, goal);
        return McpSchema.CallToolResult.builder().addTextContent(result).build();
    }

    private static McpSchema.CallToolResult handleFetchSummary(ContextManager cm, McpSchema.CallToolRequest request) {
        var args = request.arguments();
        @SuppressWarnings("unchecked")
        List<String> targets = (List<String>) args.get("targets");

        if (targets == null || targets.isEmpty()) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Missing required argument 'targets'")
                    .isError(true)
                    .build();
        }

        java.util.Set<String> classNames;
        try {
            classNames = cm.getAnalyzer().getAllDeclarations().stream()
                    .filter(ai.brokk.analyzer.CodeUnit::isClass)
                    .map(ai.brokk.analyzer.CodeUnit::fqName)
                    .collect(java.util.stream.Collectors.toSet());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Operation interrupted")
                    .isError(true)
                    .build();
        }

        var filePatterns = new ArrayList<String>();
        var classes = new ArrayList<String>();

        for (var target : targets.stream().filter(s -> !s.isBlank()).toList()) {
            if (classNames.contains(target)) {
                classes.add(target);
            } else {
                filePatterns.add(target);
            }
        }

        var searchTools = new SearchTools(cm);
        var sb = new StringBuilder();
        if (!filePatterns.isEmpty()) {
            sb.append(searchTools.getFileSummaries(filePatterns));
        }
        if (!classes.isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(searchTools.getClassSkeletons(classes));
        }

        return McpSchema.CallToolResult.builder()
                .addTextContent(sb.toString().strip())
                .build();
    }

    private static McpSchema.CallToolResult handleFetchSource(ContextManager cm, McpSchema.CallToolRequest request) {
        var args = request.arguments();
        @SuppressWarnings("unchecked")
        List<String> targets = (List<String>) args.get("targets");

        if (targets == null || targets.isEmpty()) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Missing required argument 'targets'")
                    .isError(true)
                    .build();
        }

        java.util.Set<String> classNames;
        try {
            var analyzer = cm.getAnalyzer();
            classNames = analyzer.getAllDeclarations().stream()
                    .filter(ai.brokk.analyzer.CodeUnit::isClass)
                    .map(ai.brokk.analyzer.CodeUnit::fqName)
                    .collect(java.util.stream.Collectors.toSet());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Operation interrupted")
                    .isError(true)
                    .build();
        }

        var cleaned = targets.stream().filter(s -> !s.isBlank()).toList();
        var classes = cleaned.stream().filter(classNames::contains).toList();
        var methods = cleaned.stream().filter(t -> !classNames.contains(t)).toList();

        var searchTools = new SearchTools(cm);
        var sb = new StringBuilder();
        if (!classes.isEmpty()) {
            sb.append(searchTools.getClassSources(classes));
        }
        if (!methods.isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(searchTools.getMethodSources(methods));
        }

        return McpSchema.CallToolResult.builder()
                .addTextContent(sb.toString().strip())
                .build();
    }

    private static ContextManager requireContextManager(@Nullable ContextManager cm, String toolName) {
        if (cm == null) {
            throw new IllegalStateException("Internal error: tool '" + toolName + "' requires a ContextManager");
        }
        return cm;
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
