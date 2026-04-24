package ai.brokk.acp;

import ai.brokk.LlmOutputMeta;
import ai.brokk.agents.BlitzForge;
import ai.brokk.cli.MemoryConsole;
import ai.brokk.tools.ApprovalResult;
import ai.brokk.tools.ToolExecutionResult;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessageType;
import java.awt.Component;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.JOptionPane;
import org.jetbrains.annotations.Nullable;

/**
 * IConsoleIO implementation that maps console I/O events to ACP {@code session/update}
 * notifications via a {@link SyncPromptContext}.
 *
 * <p>Extends {@link MemoryConsole} to retain an in-memory transcript (same as
 * HeadlessHttpConsole) while streaming output to the ACP client in real time.
 *
 * <p>Constructed per-prompt-turn and set as the active console on ContextManager.
 */
public class AcpConsoleIO extends MemoryConsole {
    private final SyncPromptContext context;
    private final String sessionId;

    /** Tracks tool kind from beforeToolCall so afterToolOutput can preserve it. */
    private final Map<String, AcpSchema.ToolKind> pendingToolKinds = new ConcurrentHashMap<>();

    /** Tracks tool call ID for commandStart/commandResult lifecycle. */
    private volatile @Nullable String activeCommandToolCallId;

    public AcpConsoleIO(SyncPromptContext context) {
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
        } else {
            context.sendMessage(token);
        }
    }

    // ---- Tool call hooks ----

    @Override
    public ApprovalResult beforeToolCall(ToolExecutionRequest request, boolean destructive) {
        var toolName = request.name() != null ? request.name() : "unknown";
        var toolCallId = request.id() != null ? request.id() : UUID.randomUUID().toString();
        var kind = classifyTool(toolName, destructive);
        pendingToolKinds.put(toolCallId, kind);

        var toolCall = new AcpSchema.ToolCall(
                "tool_call",
                toolCallId,
                toolName,
                kind,
                AcpSchema.ToolCallStatus.PENDING,
                List.of(),
                List.of(),
                request.arguments(),
                null,
                Map.of("brokk", Map.of("toolName", toolName)));
        context.sendUpdate(sessionId, toolCall);

        if (destructive) {
            boolean approved = context.askPermission("Allow destructive tool: " + toolName + "?");
            if (!approved) {
                pendingToolKinds.remove(toolCallId);
            }
            return approved ? ApprovalResult.APPROVED : ApprovalResult.DENIED;
        }
        return ApprovalResult.APPROVED;
    }

    @Override
    public ApprovalResult beforeShellCommand(String command) {
        var toolCallId = UUID.randomUUID().toString();
        pendingToolKinds.put(toolCallId, AcpSchema.ToolKind.EXECUTE);

        var toolCall = new AcpSchema.ToolCall(
                "tool_call",
                toolCallId,
                "shell",
                AcpSchema.ToolKind.EXECUTE,
                AcpSchema.ToolCallStatus.PENDING,
                List.of(),
                List.of(),
                command,
                null,
                Map.of("brokk", Map.of("toolName", "shell")));
        context.sendUpdate(sessionId, toolCall);

        boolean approved = context.askPermission("Allow shell command: " + command + "?");
        if (!approved) {
            pendingToolKinds.remove(toolCallId);
        }
        return approved ? ApprovalResult.APPROVED : ApprovalResult.DENIED;
    }

    @Override
    public void afterToolOutput(ToolExecutionResult result) {
        var status = result.status() == ToolExecutionResult.Status.SUCCESS
                ? AcpSchema.ToolCallStatus.COMPLETED
                : AcpSchema.ToolCallStatus.FAILED;
        var toolId = result.toolId();
        var kind = pendingToolKinds.getOrDefault(toolId, AcpSchema.ToolKind.OTHER);
        pendingToolKinds.remove(toolId);

        // Build content blocks with the result text
        List<AcpSchema.ToolCallContent> content = List.of();
        var resultText = result.resultText();
        if (resultText != null && !resultText.isBlank()) {
            content = List.of(new AcpSchema.ToolCallContentBlock(
                    "content", new AcpSchema.TextContent(resultText)));
        }

        var update = new AcpSchema.ToolCallUpdateNotification(
                "tool_call_update",
                toolId,
                result.toolName(),
                kind,
                status,
                content,
                List.of(),
                null,
                resultText,
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
        context.sendMessage("\n" + message + "\n");
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
        context.sendMessage("\n**Error (" + title + "):** " + msg + "\n");
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
        activeCommandToolCallId = toolCallId;

        var toolCall = new AcpSchema.ToolCall(
                "tool_call",
                toolCallId,
                stage,
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
            // No matching commandStart; fall back to message
            if (!success) {
                context.sendMessage("\n**" + stage + " failed:** `" + command + "`\n");
            }
            return;
        }

        var status = success ? AcpSchema.ToolCallStatus.COMPLETED : AcpSchema.ToolCallStatus.FAILED;
        var resultText = success ? output : (exception != null && !exception.isBlank() ? exception : output);

        List<AcpSchema.ToolCallContent> content = List.of();
        if (resultText != null && !resultText.isBlank()) {
            content = List.of(new AcpSchema.ToolCallContentBlock(
                    "content", new AcpSchema.TextContent(resultText)));
        }

        var update = new AcpSchema.ToolCallUpdateNotification(
                "tool_call_update",
                toolCallId,
                stage,
                AcpSchema.ToolKind.EXECUTE,
                status,
                content,
                List.of(),
                command,
                resultText,
                null);
        context.sendUpdate(sessionId, update);
    }

    // ---- BlitzForge listener ----

    @Override
    public BlitzForge.Listener getBlitzForgeListener(Runnable cancelCallback) {
        return unused -> AcpConsoleIO.this;
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
        if (toolName.startsWith("get") || toolName.startsWith("list")
                || toolName.startsWith("skim") || toolName.startsWith("explain")
                || toolName.startsWith("read") || toolName.startsWith("scan")) {
            return AcpSchema.ToolKind.READ;
        }
        if (toolName.startsWith("add") || toolName.startsWith("drop")
                || toolName.startsWith("replace") || toolName.startsWith("edit")
                || toolName.startsWith("write") || toolName.startsWith("create")) {
            return AcpSchema.ToolKind.EDIT;
        }
        return AcpSchema.ToolKind.OTHER;
    }

    // ---- GUI-only methods: all no-op (inherited defaults from IConsoleIO) ----
}
