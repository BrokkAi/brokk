package ai.brokk.mcpserver;

import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class McpToolCallHistoryWriter implements ToolRegistry.ToolCallRecorder {
    private static final Logger logger = LogManager.getLogger(McpToolCallHistoryWriter.class);

    private static final DateTimeFormatter RUN_DIR_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withLocale(Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path runDirectory;
    private final AtomicInteger counter = new AtomicInteger();

    public McpToolCallHistoryWriter(Path historyRootDirectory) throws IOException {
        Files.createDirectories(historyRootDirectory);

        String runDirName = RUN_DIR_FORMATTER.format(Instant.now());
        this.runDirectory = historyRootDirectory.resolve(runDirName);
        Files.createDirectories(runDirectory);
    }

    @Override
    public void record(ToolExecutionRequest request, ToolExecutionResult.Status status, String responseBody) {
        int idx = counter.getAndIncrement();
        String fileName = "%03d-%s.log".formatted(idx, sanitizeFilenameComponent(request.name()));
        Path logFile = runDirectory.resolve(fileName);

        String requestJson = prettyJson(buildRequestNode(request));
        String responseJson = prettyJson(buildResponseNode(status, responseBody));

        String content =
                """
                # Request

                %s

                # Response

                %s
                """
                        .formatted(requestJson, responseJson)
                        .stripIndent();

        try {
            Files.writeString(
                    logFile, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException createNewFailed) {
            try {
                Files.writeString(
                        logFile,
                        content,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } catch (IOException e) {
                logger.warn("Failed to write MCP history log file: {}", logFile, e);
            }
        }
    }

    private JsonNode buildRequestNode(ToolExecutionRequest request) {
        var root = mapper.createObjectNode();
        root.put("name", request.name());

        String args = request.arguments();
        if (args == null || args.isBlank()) {
            root.set("arguments", mapper.createObjectNode());
        } else {
            try {
                root.set("arguments", mapper.readTree(args));
            } catch (IOException e) {
                root.put("arguments", args);
            }
        }

        return root;
    }

    private JsonNode buildResponseNode(ToolExecutionResult.Status status, String responseBody) {
        var root = mapper.createObjectNode();
        root.put("status", status.name());
        root.put("response", responseBody);
        return root;
    }

    private String prettyJson(JsonNode node) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (IOException e) {
            return node.toString();
        }
    }

    private static String sanitizeFilenameComponent(String s) {
        if (s.isBlank()) {
            return "tool";
        }

        var out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.'
                    || c == '_'
                    || c == '-';
            out.append(ok ? c : '_');
        }
        return out.toString();
    }
}
