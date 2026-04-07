package ai.brokk.mcpserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class McpToolCallHistoryWriter {
    private static final Logger logger = LogManager.getLogger(McpToolCallHistoryWriter.class);

    private static final DateTimeFormatter RUN_DIR_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withLocale(Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withLocale(Locale.ROOT).withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path runDirectory;
    private final AtomicInteger counter = new AtomicInteger();
    /** Tracks which log files have already had the "# Progress" header written. */
    private final Set<Path> progressHeaderWritten = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public McpToolCallHistoryWriter(Path historyRootDirectory) throws IOException {
        Files.createDirectories(historyRootDirectory);
        String runDirName = RUN_DIR_FORMATTER.format(Instant.now());
        this.runDirectory = historyRootDirectory.resolve(runDirName);
        Files.createDirectories(runDirectory);
    }

    /**
     * Called when a request is received. Creates the log file with the raw MCP request JSON.
     * Returns the path to the log file so subsequent appends can find it.
     */
    public Path writeRequest(String toolName, String rawRequestJson) {
        int idx = counter.getAndIncrement();
        String fileName = "%03d-%s.log".formatted(idx, sanitizeFilenameComponent(toolName));
        Path logFile = runDirectory.resolve(fileName);

        String timestamp = TIMESTAMP_FORMATTER.format(Instant.now());
        String content = "# Request @%s\n\n%s\n".formatted(timestamp, prettyJson(rawRequestJson));

        appendToFile(logFile, content, true);
        return logFile;
    }

    /** Appends a progress entry to the log file, writing the "# Progress" header only once. */
    public void appendProgress(Path logFile, double progress, String message) {
        String timestamp = TIMESTAMP_FORMATTER.format(Instant.now());
        String line = "%s: %.1f%% = %s\n".formatted(timestamp, progress * 100.0, message);

        if (progressHeaderWritten.add(logFile)) {
            appendToFile(logFile, "\n# Progress\n" + line, false);
        } else {
            appendToFile(logFile, line, false);
        }
    }

    /** Appends the final result to the log file. */
    public void appendResult(Path logFile, String status, String responseBody) {
        String timestamp = TIMESTAMP_FORMATTER.format(Instant.now());
        String content =
                """

                # Response @%s

                ## Status: %s

                ## Body

                %s
                """
                        .formatted(timestamp, status, responseBody)
                        .stripIndent();
        appendToFile(logFile, content, false);
    }

    private void appendToFile(Path logFile, String content, boolean createNew) {
        try {
            if (createNew) {
                try {
                    Files.writeString(
                            logFile,
                            content,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE);
                } catch (IOException e) {
                    Files.writeString(
                            logFile,
                            content,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE);
                }
            } else {
                Files.writeString(
                        logFile, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            logger.warn("Failed to write MCP history log file: {}", logFile, e);
        }
    }

    private String prettyJson(String json) {
        if (json.isBlank()) {
            return "{}";
        }
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(json));
        } catch (IOException e) {
            return json;
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
