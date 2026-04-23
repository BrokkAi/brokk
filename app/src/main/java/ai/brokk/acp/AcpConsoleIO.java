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
import java.util.UUID;
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

    public AcpConsoleIO(SyncPromptContext context) {
        this.context = context;
        this.sessionId = context.getSessionId();
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
        // Emit a tool_call session update with PENDING status
        var toolCall = new AcpSchema.ToolCall(
                "tool_call",
                request.id() != null ? request.id() : UUID.randomUUID().toString(),
                request.name() != null ? request.name() : "unknown",
                destructive ? AcpSchema.ToolKind.EDIT : AcpSchema.ToolKind.OTHER,
                AcpSchema.ToolCallStatus.PENDING,
                List.of(),
                List.of(),
                request.arguments(),
                null,
                null);
        context.sendUpdate(sessionId, toolCall);

        if (destructive) {
            // Use ACP permission system for destructive operations
            return context.askPermission("Allow destructive tool: " + request.name() + "?")
                    ? ApprovalResult.APPROVED
                    : ApprovalResult.DENIED;
        }
        return ApprovalResult.APPROVED;
    }

    @Override
    public ApprovalResult beforeShellCommand(String command) {
        var toolCallId = UUID.randomUUID().toString();
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
                null);
        context.sendUpdate(sessionId, toolCall);

        return context.askPermission("Allow shell command: " + command + "?")
                ? ApprovalResult.APPROVED
                : ApprovalResult.DENIED;
    }

    @Override
    public void afterToolOutput(ToolExecutionResult result) {
        var status = result.status() == ToolExecutionResult.Status.SUCCESS
                ? AcpSchema.ToolCallStatus.COMPLETED
                : AcpSchema.ToolCallStatus.FAILED;
        var toolCall = new AcpSchema.ToolCall(
                "tool_call",
                result.toolId(),
                result.toolName(),
                AcpSchema.ToolKind.OTHER,
                status,
                List.of(),
                List.of(),
                null,
                result.resultText(),
                null);
        context.sendUpdate(sessionId, toolCall);
    }

    // ---- Notifications ----

    @Override
    public void showNotification(NotificationRole role, String message) {
        context.sendMessage("\n_" + message + "_\n");
    }

    @Override
    public void showNotification(NotificationRole role, String message, @Nullable Double cost) {
        if (role == NotificationRole.COST && cost != null) {
            context.sendMessage("\n_" + message + "_\n");
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

    // ---- Command execution ----

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
        context.sendMessage("\n**" + stage + ":** `" + command + "`\n");
    }

    @Override
    public void commandResult(
            String stage, String command, boolean success, String output, @Nullable String exception) {
        if (!success) {
            var msg = "**" + stage + " failed:** `" + command + "`";
            if (exception != null && !exception.isBlank()) {
                msg += "\n```\n" + exception + "\n```";
            }
            context.sendMessage("\n" + msg + "\n");
        }
    }

    // ---- BlitzForge listener ----

    @Override
    public BlitzForge.Listener getBlitzForgeListener(Runnable cancelCallback) {
        return unused -> AcpConsoleIO.this;
    }

    // ---- GUI-only methods: all no-op (inherited defaults from IConsoleIO) ----
}
