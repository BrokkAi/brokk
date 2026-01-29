package ai.brokk.tools.diagnostics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

/**
 * Parses a project's .brokk/llm-history layout (as written by ai.brokk.Llm) and
 * reconstructs JobTimeline + CallTimeline information suitable for diagnostics.
 *
 * Notes:
 * - This parser is intentionally tolerant: missing or malformed pieces do not throw;
 *   they instead result in null fields on the produced DTOs.
 */
public final class LlmHistoryParser {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // Directory names
    private static final String BROKK_DIR = ".brokk";
    private static final String HISTORY_DIR = "llm-history";

    // Filename patterns
    // Example: "14-03.27 001-request.json" -> timePrefix=14-03.27 seq=001
    private static final Pattern REQUEST_FILE_PATTERN =
            Pattern.compile("^(\\d{2}-\\d{2}\\.\\d{2})\\s+(\\d+)-request\\.json$");
    // Example: "14-03.29 001-Response.log" -> timePrefix=14-03.29 seq=001
    private static final Pattern LOG_FILE_PATTERN = Pattern.compile("^(\\d{2}-\\d{2}\\.\\d{2})\\s+(\\d+)-.*\\.log$");

    // Task directory timestamp prefix: "yyyy-MM-dd-HH-mm-ss ..."
    private static final Pattern TASK_DIR_TS_PREFIX = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2})");

    private static final DateTimeFormatter TASK_DIR_TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
    private static final DateTimeFormatter FILE_TS_FMT = DateTimeFormatter.ofPattern("HH-mm.ss");

    private LlmHistoryParser() {}

    /**
     * Parse llm-history for the given project root and return a list of JobTimeline objects,
     * one per task directory discovered under .brokk/llm-history.
     *
     * The method is tolerant: I/O or parse errors for individual calls will not abort parsing other calls.
     */
    public static List<JobTimeline> parse(Path projectRoot) {
        Path historyRoot = projectRoot.resolve(BROKK_DIR).resolve(HISTORY_DIR);
        if (!Files.exists(historyRoot)) {
            return List.of();
        }

        var jobTimelines = new ArrayList<JobTimeline>();

        try (Stream<Path> walk = Files.walk(historyRoot)) {
            // Filter directories that are task directories (exclude the root itself)
            List<Path> taskDirs = walk.filter(Files::isDirectory)
                    .filter(p -> !p.equals(historyRoot))
                    .collect(Collectors.toList());

            for (Path taskDir : taskDirs) {
                try {
                    var jt = parseTaskDirectory(taskDir);
                    if (jt != null) {
                        jobTimelines.add(jt);
                    }
                } catch (Exception e) {
                    // Continue on errors per-task but log (can't use logger conveniently here)
                    // Swallow to maintain tolerant behavior
                }
            }
        } catch (IOException e) {
            // Unable to walk tree -> return what we have
        }

        return jobTimelines;
    }

    @Nullable
    private static JobTimeline parseTaskDirectory(Path taskDir) {
        try {
            // Read all files in directory
            Map<String, Path> requestBySeq = new HashMap<>();
            Map<String, List<Path>> logsBySeq = new HashMap<>();
            Map<String, String> requestTimeBySeq = new HashMap<>();
            Map<String, String> logTimeBySeq = new HashMap<>();

            try (DirectoryStream<Path> ds = Files.newDirectoryStream(taskDir)) {
                for (Path p : ds) {
                    String name = p.getFileName().toString();
                    Matcher rm = REQUEST_FILE_PATTERN.matcher(name);
                    Matcher lm = LOG_FILE_PATTERN.matcher(name);
                    if (rm.matches()) {
                        String timePrefix = rm.group(1);
                        String seq = rm.group(2);
                        requestBySeq.put(seq, p);
                        requestTimeBySeq.put(seq, timePrefix);
                    } else if (lm.matches()) {
                        String timePrefix = lm.group(1);
                        String seq = lm.group(2);
                        logsBySeq.computeIfAbsent(seq, k -> new ArrayList<>()).add(p);
                        logTimeBySeq.put(seq, timePrefix);
                    }
                }
            }

            // If nothing meaningful, skip
            if (requestBySeq.isEmpty() && logsBySeq.isEmpty()) {
                return null;
            }

            // Prepare per-task context
            String taskDirName = taskDir.getFileName().toString();
            @Nullable LocalDateTime taskBaseDateTime = null;
            Matcher tm = TASK_DIR_TS_PREFIX.matcher(taskDirName);
            if (tm.find()) {
                String base = tm.group(1);
                try {
                    taskBaseDateTime = LocalDateTime.parse(base, TASK_DIR_TS_FMT);
                } catch (Exception ignored) {
                }
            }

            String instructionsPanelText = null; // heuristic: first non-system user message from first call

            // For stable ordering, sort sequence numbers numerically
            TreeSet<String> seqs = new TreeSet<>(Comparator.comparingInt(s -> Integer.parseInt(s)));
            seqs.addAll(requestBySeq.keySet());
            seqs.addAll(logsBySeq.keySet());

            var calls = new ArrayList<CallTimeline>();
            JobTimeline.ModelConfig jobModelConfig = null;

            for (String seq : seqs) {
                Path req = requestBySeq.get(seq);
                List<Path> logs = logsBySeq.getOrDefault(seq, List.of());
                String reqTimePrefix = requestTimeBySeq.get(seq);
                String logTimePrefix = logTimeBySeq.get(seq);

                // Parse request JSON (tolerant)
                String modelFromRequest = null;
                List<String> reconstructedMessages = new ArrayList<>();
                try {
                    if (req != null && Files.exists(req)) {
                        String raw = Files.readString(req, StandardCharsets.UTF_8);
                        JsonNode root = MAPPER.readTree(raw);

                        // model extraction (flexible)
                        if (root.has("model") && root.get("model").isTextual()) {
                            modelFromRequest = root.get("model").asText(null);
                        } else if (root.has("model") && root.get("model").isObject()) {
                            JsonNode m = root.get("model");
                            if (m.has("name") && m.get("name").isTextual()) {
                                modelFromRequest = m.get("name").asText(null);
                            }
                        } else if (root.has("parameters")
                                && root.get("parameters").has("model")) {
                            JsonNode m = root.get("parameters").get("model");
                            if (m.isTextual()) modelFromRequest = m.asText(null);
                        }

                        // messages extraction: look for "messages" array
                        if (root.has("messages") && root.get("messages").isArray()) {
                            for (JsonNode msg : root.get("messages")) {
                                String role = msg.has("role") && msg.get("role").isTextual()
                                        ? msg.get("role").asText()
                                        : null;
                                String text = extractTextFromMessageNode(msg);
                                if (text != null && !text.isBlank()) {
                                    reconstructedMessages.add((role == null ? "unknown" : role) + ": " + text);
                                    if (instructionsPanelText == null && "user".equalsIgnoreCase(role)) {
                                        instructionsPanelText = text;
                                    }
                                }
                            }
                        } else {
                            // Try other shapes: maybe single "text" field
                            String alt = extractTextFromMessageNode(root);
                            if (alt != null) {
                                reconstructedMessages.add(alt);
                                if (instructionsPanelText == null) instructionsPanelText = alt;
                            }
                        }
                    }
                } catch (IOException e) {
                    // ignore, leave fields null
                }

                // Parse log (choose first log file, if any)
                String logContent = null;
                String modelFromLogHeader = null;
                String reasoningContent = null;
                String completionText = null;
                List<ToolCallTimeline> toolCalls = List.of();
                try {
                    if (!logs.isEmpty()) {
                        Path chosen = logs.get(0);
                        logContent = Files.readString(chosen, StandardCharsets.UTF_8);

                        // header "# Request to <model>:" on first line(s)
                        Pattern header = Pattern.compile("^# Request to (.*?):", Pattern.MULTILINE);
                        Matcher hm = header.matcher(logContent);
                        if (hm.find()) {
                            modelFromLogHeader = hm.group(1).trim();
                        }

                        // Extract sections by marker positions
                        reasoningContent = extractSection(logContent, "## reasoningContent");
                        completionText = extractSection(logContent, "## text");
                        String toolJson = extractSection(logContent, "## toolExecutionRequests");
                        if (toolJson != null && !toolJson.isBlank()) {
                            try {
                                JsonNode arr = MAPPER.readTree(toolJson.trim());
                                if (arr.isArray()) {
                                    var list = new ArrayList<ToolCallTimeline>();
                                    for (JsonNode el : arr) {
                                        String name =
                                                el.has("name") ? el.get("name").asText(null) : null;
                                        String args = el.has("arguments")
                                                ? el.get("arguments").toString()
                                                : null;
                                        var t = new ToolCallTimeline(
                                                name == null ? "(unknown)" : name,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                args,
                                                null);
                                        list.add(t);
                                    }
                                    toolCalls = List.copyOf(list);
                                }
                            } catch (JsonProcessingException e) {
                                // ignore malformed tool JSON
                            }
                        }
                    }
                } catch (IOException e) {
                    // ignore
                }

                // Determine model preference: request first, then header
                String chosenModel = modelFromRequest != null ? modelFromRequest : modelFromLogHeader;

                // On first call, capture job-level model info
                if (jobModelConfig == null && chosenModel != null) {
                    jobModelConfig = new JobTimeline.ModelConfig(chosenModel, null, null);
                }

                // Timestamps: derive start and end epoch millis if possible
                Long startEpoch = tryComposeEpoch(taskBaseDateTime, reqTimePrefix);
                Long endEpoch = tryComposeEpoch(taskBaseDateTime, logTimePrefix);

                Long durationMs = (startEpoch != null && endEpoch != null) ? (endEpoch - startEpoch) : null;

                // Build promptRaw by joining reconstructedMessages
                String promptRaw = reconstructedMessages.isEmpty() ? null : String.join("\n\n", reconstructedMessages);

                // Build CallTimeline
                String callId = taskDir.getFileName().toString() + "-" + seq;
                JobTimeline.ModelConfig callModelConfig =
                        chosenModel == null ? null : new JobTimeline.ModelConfig(chosenModel, null, null);

                CallTimeline ct = new CallTimeline(
                        callId,
                        taskDir.getFileName().toString(), // jobId
                        null, // sessionId
                        null, // phaseId
                        startEpoch,
                        endEpoch,
                        durationMs,
                        callModelConfig,
                        null, // modelRole
                        promptRaw,
                        promptRaw == null ? null : Boolean.FALSE,
                        null, // promptTokenCount
                        completionText,
                        completionText == null ? null : Boolean.FALSE,
                        null, // completionTokenCount
                        reasoningContent,
                        null, // thoughtSignature
                        toolCalls,
                        Integer.parseInt(seq),
                        (completionText == null && reasoningContent == null && toolCalls.isEmpty())
                                ? "UNKNOWN"
                                : "COMPLETED");

                calls.add(ct);
            }

            // Build job aggregates
            Map<String, Object> aggregates = new HashMap<>();
            if (instructionsPanelText != null) {
                aggregates.put("instructionsPanelText", instructionsPanelText);
            }

            // Job-level timestamps: try to get from taskBaseDateTime if available
            Long jobStart = null;
            if (taskBaseDateTime != null) {
                jobStart = taskBaseDateTime
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
            }

            JobTimeline jt = new JobTimeline(
                    taskDir.getFileName().toString(),
                    null,
                    null,
                    jobStart,
                    null,
                    null,
                    jobModelConfig,
                    null,
                    List.of(), // phases - unknown
                    List.copyOf(calls),
                    aggregates.isEmpty() ? Map.of() : aggregates);
            return jt;
        } catch (IOException e) {
            return null;
        }
    }

    @Nullable
    private static String extractTextFromMessageNode(JsonNode msg) {
        if (msg == null || msg.isNull()) return null;

        // Common shapes: { "content": "text" } OR { "text": "..." } OR OpenAI: { "content": [ { "type":"text",
        // "text":"..." } ] }
        if (msg.has("content")) {
            JsonNode c = msg.get("content");
            if (c.isTextual()) {
                return c.asText();
            } else if (c.isArray()) {
                var parts = new ArrayList<String>();
                for (JsonNode el : c) {
                    if (el.has("text") && el.get("text").isTextual()) {
                        parts.add(el.get("text").asText());
                    } else if (el.isTextual()) {
                        parts.add(el.asText());
                    } else {
                        parts.add(el.toString());
                    }
                }
                return String.join("\n", parts);
            } else if (c.isObject() && c.has("text") && c.get("text").isTextual()) {
                return c.get("text").asText();
            } else {
                return c.toString();
            }
        }

        if (msg.has("text") && msg.get("text").isTextual()) {
            return msg.get("text").asText();
        }

        // fallback: entire node as string if it's short
        String repr = msg.toString();
        if (repr.length() < 1000) return repr;
        return null;
    }

    @Nullable
    private static String extractSection(String content, String sectionMarker) {
        if (content == null || content.isBlank()) return null;
        int idx = content.indexOf(sectionMarker);
        if (idx == -1) return null;
        int start = idx + sectionMarker.length();
        // Skip following newline characters
        while (start < content.length() && (content.charAt(start) == '\n' || content.charAt(start) == '\r')) start++;

        // find next "## " header after start
        int nextIdx = content.indexOf("\n## ", start);
        if (nextIdx == -1) {
            // read to end
            return content.substring(start).strip();
        } else {
            return content.substring(start, nextIdx).strip();
        }
    }

    @Nullable
    private static Long tryComposeEpoch(@Nullable LocalDateTime base, @Nullable String hhmmss) {
        if (base == null || hhmmss == null) return null;
        try {
            LocalTime fileTime = LocalTime.parse(hhmmss, FILE_TS_FMT);
            LocalDateTime combined = LocalDateTime.of(base.toLocalDate(), fileTime);
            return combined.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }
}
