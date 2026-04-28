package ai.brokk.acp;

import ai.brokk.LlmOutputMeta;
import ai.brokk.agents.BlitzForge;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.cli.MemoryConsole;
import ai.brokk.tools.ApprovalResult;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.util.Json;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessageType;
import java.awt.Component;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JOptionPane;
import org.jetbrains.annotations.Nullable;

/**
 * IConsoleIO implementation that maps console I/O events to ACP {@code session/update}
 * notifications via a {@link AcpPromptContext}.
 *
 * <p>Extends {@link MemoryConsole} to retain an in-memory transcript (same as
 * HeadlessHttpConsole) while streaming output to the ACP client in real time.
 *
 * <p>Constructed per-prompt-turn and set as the active console on ContextManager.
 */
public class AcpConsoleIO extends MemoryConsole {
    private final AcpPromptContext context;
    private final String sessionId;

    /** Tracks tool kind from beforeToolCall so afterToolOutput can preserve it. */
    private final Map<String, AcpSchema.ToolKind> pendingToolKinds = new ConcurrentHashMap<>();

    /** Tracks the human-readable title computed at beforeToolCall so the lifecycle stays stable. */
    private final Map<String, String> pendingToolTitles = new ConcurrentHashMap<>();

    /** Tracks tool call ID for commandStart/commandResult lifecycle. */
    private volatile @Nullable String activeCommandToolCallId;

    /**
     * Tools whose result text is the agent's user-facing answer and is also streamed via
     * {@code llmOutput} as the tool body. For these we suppress the tool-call body to avoid
     * double-rendering the answer; the wrapping {@code tool_call_update} still fires (with
     * empty content and a "Final answer" / "Workspace ready" / etc. title) so the structural
     * shape — every action is a tool call — is preserved.
     */
    private static final Set<String> TERMINAL_ANSWER_TOOLS = Set.of(
            "answer",
            "workspaceComplete",
            "abortSearch",
            "askForClarification",
            "describeIssue",
            "projectFinished",
            "abortProject");

    /** Cap on rendered tool-call body bytes. */
    private static final int MAX_RENDERED_BODY_BYTES = 8 * 1024;

    /** Cap for SEARCH bullet lists. */
    private static final int MAX_SEARCH_HITS = 30;

    /** Pattern for extracting a leading bold "header" from a status banner. */
    private static final Pattern BOLD_HEADER = Pattern.compile("^\\*\\*([^*\\n]+)\\*\\*[:\\s]*(.*)", Pattern.DOTALL);

    /**
     * Pattern for extracting a leading backtick-quoted "header" from an ExplanationRenderer-style
     * status banner (e.g. {@code `Adding context to workspace`} followed by a YAML block).
     */
    private static final Pattern BACKTICK_HEADER = Pattern.compile("^`([^`\\n]+)`[:\\s]*(.*)", Pattern.DOTALL);

    private record ActiveEdit(String toolCallId, String toolName) {}

    /**
     * Per-thread stack of active EDIT tool calls. EditBlock.apply runs synchronously on the same
     * thread that called beforeToolCall, so afterFileEdits can correlate by inspecting the top.
     * Using a stack (rather than a single ref) supports nested EDIT tools — e.g. an architect tool
     * that calls a code agent tool — without overwriting the outer tool's id.
     */
    private static final ThreadLocal<Deque<ActiveEdit>> ACTIVE_EDIT_STACK = ThreadLocal.withInitial(ArrayDeque::new);

    public AcpConsoleIO(AcpPromptContext context) {
        this.context = context;
        this.sessionId = context.getSessionId();
    }

    // ---- Structured tool output ----

    @Override
    public boolean supportsStructuredToolOutput() {
        return true;
    }

    // ---- Core LLM output ----

    @Override
    public void llmOutput(String token, ChatMessageType type, LlmOutputMeta meta) {
        super.llmOutput(token, type, meta);
        if (meta.isReasoning()) {
            context.sendThought(token);
            return;
        }
        if (type == ChatMessageType.CUSTOM) {
            // CUSTOM emissions are always status banners; wrap them as synthetic tool calls so
            // they don't masquerade as assistant chat text.
            emitSyntheticToolCall(splitHeader(token), AcpSchema.ToolKind.THINK);
            return;
        }
        if (type == ChatMessageType.AI) {
            // Brokk's agents emit "**Brokk Context Engine** analyzing…" /
            // "`Adding context to workspace`\n```yaml\n…" status banners as full-message AI
            // emissions. Detect them by *complete* `**Header**` or `` `Header` `` shape with
            // content after — this excludes streaming tokens (partial markers) and bare bold
            // emphasis (no content after the closing marker), and catches banners regardless
            // of the LlmOutputMeta.newMessage flag (which Brokk doesn't set consistently).
            // Final-answer text from terminal-answer tools is emitted as `# Answer\n\n…`
            // (h1, not bold/backtick), so it doesn't match and continues flowing to chat.
            var banner = tryDetectBanner(token);
            if (banner.isPresent()) {
                emitSyntheticToolCall(banner.get(), AcpSchema.ToolKind.THINK);
                return;
            }
        }
        context.sendMessage(token);
    }

    /**
     * Returns the header/body split when {@code text} matches a complete banner pattern:
     * either {@code **Header** rest} or {@code `Header` rest}, where {@code rest} is non-blank.
     * Empty when the text is normal chat content (incl. streaming tokens that are partial
     * markers, bare bold/code emphasis with no body, or h1/plain text).
     */
    private static Optional<HeaderAndBody> tryDetectBanner(String text) {
        var trimmed = text.strip();
        Matcher m = BOLD_HEADER.matcher(trimmed);
        if (m.matches()) {
            var header = m.group(1).strip();
            var rest = m.group(2).stripLeading();
            if (!header.isBlank() && !rest.isBlank()) {
                return Optional.of(new HeaderAndBody(header, rest));
            }
        }
        m = BACKTICK_HEADER.matcher(trimmed);
        if (m.matches()) {
            var header = m.group(1).strip();
            var rest = m.group(2).stripLeading();
            if (!header.isBlank() && !rest.isBlank()) {
                return Optional.of(new HeaderAndBody(header, rest));
            }
        }
        return Optional.empty();
    }

    // ---- Tool call hooks ----

    @Override
    public ApprovalResult beforeToolCall(ToolExecutionRequest request, boolean destructive) {
        var toolName = request.name() != null ? request.name() : "unknown";
        var toolCallId = request.id() != null ? request.id() : UUID.randomUUID().toString();
        var kind = classifyTool(toolName, destructive);
        var title = displayTitle(toolName, request.arguments());
        pendingToolKinds.put(toolCallId, kind);
        pendingToolTitles.put(toolCallId, title);
        if (kind == AcpSchema.ToolKind.EDIT) {
            ACTIVE_EDIT_STACK.get().push(new ActiveEdit(toolCallId, toolName));
        }

        var toolCall = new AcpSchema.ToolCall(
                "tool_call",
                toolCallId,
                title,
                kind,
                AcpSchema.ToolCallStatus.PENDING,
                List.of(),
                List.of(),
                request.arguments(),
                null,
                Map.of("brokk", Map.of("toolName", toolName)));
        context.sendUpdate(sessionId, toolCall);

        if (destructive) {
            boolean approved = context.askPermission("Allow destructive tool: " + toolName + "?", toolName);
            if (!approved) {
                pendingToolKinds.remove(toolCallId);
                pendingToolTitles.remove(toolCallId);
            }
            return approved ? ApprovalResult.APPROVED : ApprovalResult.DENIED;
        }
        return ApprovalResult.APPROVED;
    }

    @Override
    public ApprovalResult beforeShellCommand(String command) {
        var toolCallId = UUID.randomUUID().toString();
        var title = "Shell: " + truncate(command, 60);
        pendingToolKinds.put(toolCallId, AcpSchema.ToolKind.EXECUTE);
        pendingToolTitles.put(toolCallId, title);

        var toolCall = new AcpSchema.ToolCall(
                "tool_call",
                toolCallId,
                title,
                AcpSchema.ToolKind.EXECUTE,
                AcpSchema.ToolCallStatus.PENDING,
                List.of(),
                List.of(),
                command,
                null,
                Map.of("brokk", Map.of("toolName", "shell")));
        context.sendUpdate(sessionId, toolCall);

        boolean approved = context.askPermission("Allow shell command: " + command + "?", "shell");
        if (!approved) {
            pendingToolKinds.remove(toolCallId);
            pendingToolTitles.remove(toolCallId);
        }
        return approved ? ApprovalResult.APPROVED : ApprovalResult.DENIED;
    }

    /** Maximum bytes of pre/post text we'll ship inside a single ToolCallDiff content block. */
    private static final int MAX_DIFF_BLOB_BYTES = 256 * 1024;

    @Override
    public void afterFileEdits(Map<ProjectFile, String> originalContents, Map<ProjectFile, String> newContents) {
        var active = ACTIVE_EDIT_STACK.get().peek();
        if (active == null) {
            return;
        }
        var toolCallId = active.toolCallId();
        var toolName = active.toolName();
        var title = pendingToolTitles.getOrDefault(toolCallId, displayTitle(toolName, null));
        var diffs = originalContents.entrySet().stream()
                .map(e -> (AcpSchema.ToolCallContent) new AcpSchema.ToolCallDiff(
                        "diff",
                        e.getKey().absPath().toString(),
                        truncateForDiff(e.getValue()),
                        truncateForDiff(newContents.getOrDefault(e.getKey(), ""))))
                .toList();
        var update = new AcpSchema.ToolCallUpdateNotification(
                "tool_call_update",
                toolCallId,
                title,
                AcpSchema.ToolKind.EDIT,
                AcpSchema.ToolCallStatus.IN_PROGRESS,
                diffs,
                List.of(),
                null,
                null,
                null);
        context.sendUpdate(sessionId, update);
    }

    @Override
    public void toolCallInProgress(ToolExecutionRequest request) {
        var toolCallId = request.id() != null ? request.id() : null;
        if (toolCallId == null) {
            return;
        }
        var kind = pendingToolKinds.getOrDefault(toolCallId, AcpSchema.ToolKind.OTHER);
        var title = pendingToolTitles.getOrDefault(toolCallId, displayTitle(request.name(), request.arguments()));
        var update = new AcpSchema.ToolCallUpdateNotification(
                "tool_call_update",
                toolCallId,
                title,
                kind,
                AcpSchema.ToolCallStatus.IN_PROGRESS,
                List.of(),
                List.of(),
                null,
                null,
                null);
        context.sendUpdate(sessionId, update);
    }

    @Override
    public void afterToolOutput(ToolExecutionResult result) {
        var status = result.status() == ToolExecutionResult.Status.SUCCESS
                ? AcpSchema.ToolCallStatus.COMPLETED
                : AcpSchema.ToolCallStatus.FAILED;
        var toolId = result.toolId();
        var kind = pendingToolKinds.getOrDefault(toolId, AcpSchema.ToolKind.OTHER);
        pendingToolKinds.remove(toolId);
        var title = pendingToolTitles.getOrDefault(toolId, displayTitle(result.toolName(), null));
        pendingToolTitles.remove(toolId);
        var stack = ACTIVE_EDIT_STACK.get();
        // Pop the matching entry from the top of the per-thread edit stack. We use removeIf
        // (single match) because nesting may not be perfectly LIFO if an exception unwound
        // partially without afterToolOutput firing.
        stack.removeIf(e -> toolId.equals(e.toolCallId()));
        if (stack.isEmpty()) {
            ACTIVE_EDIT_STACK.remove();
        }

        // Terminal-answer tools: the resultText is the user-visible answer and was already
        // streamed via llmOutput → agent_message_chunk. Suppress the body to avoid duplication;
        // keep the wrapper so the action is still represented in the tool-call timeline.
        List<AcpSchema.ToolCallContent> content = TERMINAL_ANSWER_TOOLS.contains(result.toolName())
                ? List.of()
                : renderToolContent(kind, result.resultText());

        var update = new AcpSchema.ToolCallUpdateNotification(
                "tool_call_update",
                toolId,
                title,
                kind,
                status,
                content,
                List.of(),
                null,
                null,
                Map.of("brokk", Map.of("toolName", result.toolName())));
        context.sendUpdate(sessionId, update);
    }

    // ---- Notifications ----

    @Override
    public void showNotification(NotificationRole role, String message) {
        // Cost notifications are noise in the ACP stream; skip them
        if (role == NotificationRole.COST) {
            return;
        }
        emitSyntheticToolCall(new HeaderAndBody("Notification", message), AcpSchema.ToolKind.OTHER);
    }

    @Override
    public void showNotification(NotificationRole role, String message, @Nullable Double cost) {
        if (role == NotificationRole.COST) {
            // Deliver cost info as a usage_update if we have a numeric cost
            if (cost != null) {
                var costObj = new AcpSchema.Cost(cost, "USD");
                var usage = new AcpSchema.UsageUpdate("usage_update", null, null, costObj, null);
                context.sendUpdate(sessionId, usage);
            }
            return;
        }
        showNotification(role, message);
    }

    @Override
    public void toolError(String msg, String title) {
        emitSyntheticToolCall(new HeaderAndBody("Error: " + title, msg), AcpSchema.ToolKind.OTHER);
    }

    // ---- Confirm dialogs -> ACP permissions ----

    @Override
    public int showConfirmDialog(String message, String title, int optionType, int messageType) {
        boolean approved = context.askPermission(title + ": " + message);
        return approved ? JOptionPane.YES_OPTION : JOptionPane.NO_OPTION;
    }

    @Override
    public int showConfirmDialog(
            @Nullable Component parent, String message, String title, int optionType, int messageType) {
        return showConfirmDialog(message, title, optionType, messageType);
    }

    // ---- Command execution (build/test commands) ----

    @Override
    public boolean supportsCommandResult() {
        return true;
    }

    @Override
    public void commandOutput(String line) {
        // no-op: full output delivered via commandResult
    }

    @Override
    public void commandStart(String stage, String command) {
        // Model build/test commands as tool_call with EXECUTE kind
        var toolCallId = UUID.randomUUID().toString();
        var title = stage + ": " + truncate(command, 60);
        activeCommandToolCallId = toolCallId;
        pendingToolTitles.put(toolCallId, title);

        var toolCall = new AcpSchema.ToolCall(
                "tool_call",
                toolCallId,
                title,
                AcpSchema.ToolKind.EXECUTE,
                AcpSchema.ToolCallStatus.IN_PROGRESS,
                List.of(),
                List.of(),
                command,
                null,
                Map.of("brokk", Map.of("toolName", stage, "command", command)));
        context.sendUpdate(sessionId, toolCall);
    }

    @Override
    public void commandResult(
            String stage, String command, boolean success, String output, @Nullable String exception) {
        var toolCallId = activeCommandToolCallId;
        activeCommandToolCallId = null;

        if (toolCallId == null) {
            // No matching commandStart; surface as a synthetic tool call so we don't dump bare
            // text into the chat stream.
            if (!success) {
                emitSyntheticToolCall(
                        new HeaderAndBody(stage + " failed", "`" + command + "`"), AcpSchema.ToolKind.EXECUTE);
            }
            return;
        }

        var status = success ? AcpSchema.ToolCallStatus.COMPLETED : AcpSchema.ToolCallStatus.FAILED;
        var resultText = success ? output : (exception != null && !exception.isBlank() ? exception : output);
        var title = pendingToolTitles.getOrDefault(toolCallId, stage + ": " + truncate(command, 60));
        pendingToolTitles.remove(toolCallId);

        List<AcpSchema.ToolCallContent> content = renderToolContent(AcpSchema.ToolKind.EXECUTE, resultText);

        var update = new AcpSchema.ToolCallUpdateNotification(
                "tool_call_update",
                toolCallId,
                title,
                AcpSchema.ToolKind.EXECUTE,
                status,
                content,
                List.of(),
                command,
                null,
                null);
        context.sendUpdate(sessionId, update);
    }

    // ---- BlitzForge listener ----

    @Override
    public BlitzForge.Listener getBlitzForgeListener(Runnable cancelCallback) {
        return unused -> AcpConsoleIO.this;
    }

    // ---- Synthetic tool-call wrapping for non-LLM output ----

    private record HeaderAndBody(String header, String body) {}

    /**
     * Emit a single completed {@link AcpSchema.ToolCall} carrying a non-LLM emission
     * (CUSTOM-typed status banner, notification, error). Wrapping these as tool calls keeps
     * them out of the assistant-text stream and lets clients collapse them by default.
     */
    private void emitSyntheticToolCall(HeaderAndBody parts, AcpSchema.ToolKind kind) {
        var body = parts.body() == null ? "" : parts.body();
        List<AcpSchema.ToolCallContent> content = body.isBlank()
                ? List.of()
                : List.of(new AcpSchema.ToolCallContentBlock(
                        "content", new AcpSchema.TextContent(truncate(body, MAX_RENDERED_BODY_BYTES))));
        var toolCall = new AcpSchema.ToolCall(
                "tool_call",
                UUID.randomUUID().toString(),
                parts.header(),
                kind,
                AcpSchema.ToolCallStatus.COMPLETED,
                content,
                List.of(),
                null,
                null,
                Map.of("brokk", Map.of("synthetic", true)));
        context.sendUpdate(sessionId, toolCall);
    }

    /**
     * Split a status banner into a header and body. Recognizes:
     *
     * <ul>
     *   <li>{@code **Header** rest} (CUSTOM-typed banners like {@code **Code Agent** engaged…})
     *   <li>{@code `Header` rest} (ExplanationRenderer output like
     *       {@code `Adding context to workspace`} followed by a YAML block)
     * </ul>
     *
     * Falls back to the first short line as header when no marker prefix is present.
     */
    private static HeaderAndBody splitHeader(String token) {
        var trimmed = token.strip();
        Matcher m = BOLD_HEADER.matcher(trimmed);
        if (!m.matches()) {
            m = BACKTICK_HEADER.matcher(trimmed);
        }
        if (m.matches()) {
            var header = m.group(1).strip();
            var rest = m.group(2).stripLeading();
            return new HeaderAndBody(header.isBlank() ? "Status" : header, rest);
        }
        int nl = trimmed.indexOf('\n');
        if (nl > 0 && nl < 80) {
            return new HeaderAndBody(
                    trimmed.substring(0, nl).strip(), trimmed.substring(nl + 1).stripLeading());
        }
        return new HeaderAndBody("Status", trimmed);
    }

    // ---- Title rendering ----

    /**
     * Build a human-readable title for a tool call. Recognized tools get a hand-tuned phrase
     * derived from their arguments; everything else falls back to camelCase humanization. Reference
     * the same approach in claude-agent-acp/src/tools.ts.
     */
    private static String displayTitle(@Nullable String toolName, @Nullable Object argumentsJson) {
        if (toolName == null || toolName.isBlank()) {
            return "Tool call";
        }
        @Nullable JsonNode args = parseArgs(argumentsJson);
        return switch (toolName) {
            case "findFilesContaining" -> "Search files for " + quoted(textArg(args, "pattern", "substring"));
            case "findFilenames" -> "Find filenames matching " + quoted(textArg(args, "pattern", "glob"));
            case "getFileContents" -> "Read " + textArg(args, "filename", "path");
            case "getMethodSources" -> "Read methods " + textArg(args, "fqMethodNames", "fqName");
            case "getClassSources" -> "Read class " + textArg(args, "fqClassNames", "fqName");
            case "getClassSkeletons" -> "Skim class " + textArg(args, "fqClassNames", "fqName");
            case "skimFiles" -> "Skim " + textArg(args, "filenames", "files");
            case "scanUsages" -> "Scan usages of " + textArg(args, "fqName", "symbol");
            case "searchSymbols" -> "Search symbols for " + quoted(textArg(args, "query", "pattern"));
            case "searchGitCommitMessages" -> "Search commits for " + quoted(textArg(args, "query", "pattern"));
            case "listFiles" -> "List files in " + textArg(args, "path", "directory");
            case "getGitLog" -> "Read git log";
            case "explainCommit" -> "Explain commit " + textArg(args, "commit", "ref");
            case "createOrReplaceTaskList" -> "Update task list";
            case "addFilesToWorkspace",
                    "addClassesToWorkspace",
                    "addClassSummariesToWorkspace",
                    "addFileSummariesToWorkspace",
                    "addLineRangeToWorkspace",
                    "addMethodsToWorkspace",
                    "addUrlContentsToWorkspace" -> "Add to workspace";
            case "dropWorkspaceFragments" -> "Drop workspace fragments";
            case "searchAgent" -> "Search agent: " + truncate(textArg(args, "query", "request"), 60);
            case "callCodeAgent" -> "Code agent: " + truncate(textArg(args, "instructions", "task"), 60);
            case "callShellAgent" -> "Shell agent: " + truncate(textArg(args, "task", "command"), 60);
            case "callMcpTool" -> "MCP: " + textArg(args, "toolName", "tool");
            case "answer" -> "Final answer";
            case "workspaceComplete" -> "Workspace ready";
            case "abortSearch" -> "Search aborted";
            case "askForClarification" -> "Question for the user";
            case "describeIssue" -> "Issue description";
            case "projectFinished" -> "Project complete";
            case "abortProject" -> "Project aborted";
            case "shell" -> "Shell";
            case "replaceLines" -> "Edit " + textArg(args, "filename", "file");
            case "replaceFile", "writeFile" -> "Write " + textArg(args, "filename", "file");
            case "addFile" -> "Create " + textArg(args, "filename", "file");
            default -> humanize(toolName);
        };
    }

    private static @Nullable JsonNode parseArgs(@Nullable Object argumentsJson) {
        if (argumentsJson == null) {
            return null;
        }
        var raw = argumentsJson.toString();
        if (raw.isBlank()) {
            return null;
        }
        try {
            return Json.getMapper().readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String textArg(@Nullable JsonNode args, String... fieldsToTry) {
        if (args == null) {
            return "";
        }
        for (var field : fieldsToTry) {
            var node = args.get(field);
            if (node == null || node.isNull()) {
                continue;
            }
            if (node.isTextual()) {
                return node.asText();
            }
            if (node.isArray()) {
                var sb = new java.util.StringJoiner(", ");
                int i = 0;
                for (var elt : node) {
                    if (i++ >= 4) {
                        sb.add("...");
                        break;
                    }
                    sb.add(elt.isTextual() ? elt.asText() : elt.toString());
                }
                return sb.toString();
            }
            return node.toString();
        }
        return "";
    }

    private static String quoted(String s) {
        return s.isBlank() ? "" : "'" + s + "'";
    }

    /**
     * Convert {@code getFileContents} → {@code "Get file contents"}. Used as the default title
     * for tools not in the explicit map.
     */
    private static String humanize(String camelCase) {
        if (camelCase.isEmpty()) {
            return "Tool call";
        }
        var sb = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                sb.append(' ');
            }
            sb.append(i == 0 ? Character.toUpperCase(c) : Character.toLowerCase(c));
        }
        return sb.toString();
    }

    // ---- Content rendering ----

    /**
     * Render a tool's {@code resultText} into ACP {@link AcpSchema.ToolCallContent} blocks.
     * Strategy:
     *
     * <ul>
     *   <li>Empty / blank → no content blocks.
     *   <li>MCP-shaped {@code {"content":[{"type":"text","text":...}]}} → unwrap inner text.
     *   <li>JSON array of strings (search hits) → markdown bullet list, capped at {@link
     *       #MAX_SEARCH_HITS}.
     *   <li>Other parseable JSON → pretty-printed inside a fenced ```json block so clients
     *       render it as code rather than as an escaped one-liner.
     *   <li>Plain text → pass through.
     * </ul>
     *
     * <p>All output is capped at {@link #MAX_RENDERED_BODY_BYTES}.
     */
    private static List<AcpSchema.ToolCallContent> renderToolContent(AcpSchema.ToolKind kind, String resultText) {
        if (resultText == null || resultText.isBlank()) {
            return List.of();
        }
        var rendered = renderText(kind, resultText);
        return List.of(new AcpSchema.ToolCallContentBlock("content", new AcpSchema.TextContent(rendered)));
    }

    private static String renderText(AcpSchema.ToolKind kind, String resultText) {
        var parsed = tryParseJson(resultText);
        if (parsed == null) {
            return truncate(resultText, MAX_RENDERED_BODY_BYTES);
        }
        // Unwrap MCP content envelopes
        var unwrapped = unwrapMcpContent(parsed);
        if (unwrapped != null) {
            return truncate(unwrapped, MAX_RENDERED_BODY_BYTES);
        }
        if (parsed.isArray() && (kind == AcpSchema.ToolKind.SEARCH || kind == AcpSchema.ToolKind.READ)) {
            return renderArrayAsBullets(parsed);
        }
        return renderJsonAsCodeBlock(parsed);
    }

    private static @Nullable JsonNode tryParseJson(String text) {
        var trimmed = text.stripLeading();
        if (trimmed.isEmpty()) {
            return null;
        }
        char first = trimmed.charAt(0);
        if (first != '{' && first != '[') {
            return null;
        }
        try {
            return Json.getMapper().readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * If {@code node} matches the MCP {@code {"content":[{"type":"text","text":"..."}]}} shape,
     * concatenate the inner text fields and return; otherwise null. Other MCP content types
     * (image, resource) are left structured.
     */
    private static @Nullable String unwrapMcpContent(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        var content = node.get("content");
        if (content == null || !content.isArray() || content.isEmpty()) {
            return null;
        }
        var sb = new StringBuilder();
        for (var item : content) {
            if (!item.isObject()) {
                return null;
            }
            var type = item.get("type");
            if (type == null || !"text".equals(type.asText())) {
                return null;
            }
            var text = item.get("text");
            if (text == null) {
                return null;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(text.asText());
        }
        return sb.toString();
    }

    private static String renderArrayAsBullets(JsonNode array) {
        var sb = new StringBuilder();
        int i = 0;
        for (var elt : array) {
            if (i >= MAX_SEARCH_HITS) {
                sb.append("\n... ").append(array.size() - MAX_SEARCH_HITS).append(" more");
                break;
            }
            sb.append("- ");
            if (elt.isTextual()) {
                sb.append(elt.asText());
            } else {
                sb.append(elt.toString());
            }
            sb.append('\n');
            i++;
        }
        return truncate(sb.toString(), MAX_RENDERED_BODY_BYTES);
    }

    private static String renderJsonAsCodeBlock(JsonNode node) {
        try {
            var pretty = Json.getMapper().writerWithDefaultPrettyPrinter().writeValueAsString(node);
            return "```json\n" + truncate(pretty, MAX_RENDERED_BODY_BYTES - 16) + "\n```";
        } catch (Exception e) {
            return truncate(node.toString(), MAX_RENDERED_BODY_BYTES);
        }
    }

    // ---- Truncation helpers ----

    private static String truncateForDiff(String text) {
        if (text.length() <= MAX_DIFF_BLOB_BYTES) {
            return text;
        }
        return text.substring(0, MAX_DIFF_BLOB_BYTES) + "\n... [truncated " + (text.length() - MAX_DIFF_BLOB_BYTES)
                + " bytes]";
    }

    private static String truncate(String text, int maxBytes) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxBytes) {
            return text;
        }
        return text.substring(0, maxBytes) + "\n... [truncated " + (text.length() - maxBytes) + " chars]";
    }

    // ---- Tool kind classification ----

    /**
     * Maps a Brokk tool name to the appropriate ACP {@link AcpSchema.ToolKind}.
     * Destructive tools always map to EDIT. Otherwise, classification is based on
     * tool name prefixes to match the reference ACP implementation's categories.
     */
    private static AcpSchema.ToolKind classifyTool(String toolName, boolean destructive) {
        if (destructive) {
            return AcpSchema.ToolKind.EDIT;
        }
        return switch (toolName) {
            case "shell" -> AcpSchema.ToolKind.EXECUTE;
            case "searchAgent" -> AcpSchema.ToolKind.SEARCH;
            case "createOrReplaceTaskList" -> AcpSchema.ToolKind.THINK;
            default -> classifyByPrefix(toolName);
        };
    }

    private static AcpSchema.ToolKind classifyByPrefix(String toolName) {
        if (toolName.startsWith("search") || toolName.startsWith("find")) {
            return AcpSchema.ToolKind.SEARCH;
        }
        if (toolName.startsWith("get")
                || toolName.startsWith("list")
                || toolName.startsWith("skim")
                || toolName.startsWith("explain")
                || toolName.startsWith("read")
                || toolName.startsWith("scan")) {
            return AcpSchema.ToolKind.READ;
        }
        if (toolName.startsWith("add")
                || toolName.startsWith("drop")
                || toolName.startsWith("replace")
                || toolName.startsWith("edit")
                || toolName.startsWith("write")
                || toolName.startsWith("create")) {
            return AcpSchema.ToolKind.EDIT;
        }
        return AcpSchema.ToolKind.OTHER;
    }

    // ---- GUI-only methods: all no-op (inherited defaults from IConsoleIO) ----
}
