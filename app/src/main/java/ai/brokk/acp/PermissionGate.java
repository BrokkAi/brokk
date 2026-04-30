package ai.brokk.acp;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.jetbrains.annotations.Nullable;

/**
 * Pure permission-gate logic. Given a session's {@link PermissionMode} and the kind/name of the
 * tool being invoked, decides whether to auto-allow, auto-reject, or escalate to a per-call prompt.
 * Mirrors {@code brokk-acp-rust/src/tool_loop.rs:pure_gate_decision}.
 *
 * <p>The decision is intentionally separate from {@link AcpRequestContext#askPermission} so it can
 * be unit-tested without spinning up a transport.
 */
final class PermissionGate {
    private PermissionGate() {}

    /** Outcome returned by {@link #decide}. */
    enum Outcome {
        ALLOW,
        REJECT,
        PROMPT
    }

    /** Reason text shown to the user when {@code READ_ONLY} rejects a call. */
    static final String READ_ONLY_REJECTION =
            """
            Tool use denied: read-only mode forbids edits, deletions, moves, shell execution, \
            and any tool not classified as read/search/think/fetch. \
            Switch the Permission menu to 'default' or 'acceptEdits' to run this tool.""";

    /**
     * Tools that must always escalate per call regardless of the always-allow cache. {@code "shell"}
     * is here because the cache key is the literal string "shell" and one approval would otherwise
     * blanket-allow every future shell command in the session.
     */
    private static boolean requiresPerCallPrompt(String toolName) {
        return "shell".equals(toolName);
    }

    /** Tools that pass a raw shell command string we can safety-check via {@link SafeCommand}. */
    private static boolean isShellExecutionTool(String toolName) {
        return "runShellCommand".equals(toolName) || "shell".equals(toolName);
    }

    /**
     * Returns the gate's outcome for the given mode/kind/tool/cache-state combination.
     *
     * @param isAlwaysAllowed {@code true} iff the user has previously chosen "Always allow" for
     *     this tool in this session (i.e. the sticky cache holds an ALLOW verdict).
     * @param rawCommand for shell-execution tools, the raw command string the model wants to run;
     *     used to short-circuit the prompt for known-safe read-only commands. {@code null} for
     *     non-shell tools or when the command is not available at gate time.
     */
    static Outcome decide(
            PermissionMode mode,
            AcpSchema.ToolKind kind,
            String toolName,
            boolean isAlwaysAllowed,
            @Nullable String rawCommand) {
        // BYPASS_PERMISSIONS: trust everything. Explicit user opt-out of the gate.
        if (mode == PermissionMode.BYPASS_PERMISSIONS) {
            return Outcome.ALLOW;
        }

        // READ_ONLY: only allow strictly informational kinds. OTHER (Bifrost-loaded tools we
        // haven't classified) is refused so the user-visible "no edits/exec" promise actually
        // holds. Also refused even if always-allow was set, because READ_ONLY is a hard brake.
        if (mode == PermissionMode.READ_ONLY && !isReadOnlyKind(kind)) {
            return Outcome.REJECT;
        }

        // Mode-independent auto-allow: pure-info kinds never mutate.
        if (isReadOnlyKind(kind)) {
            return Outcome.ALLOW;
        }

        // ACCEPT_EDITS auto-allows EDIT but still gates EXECUTE/OTHER.
        if (mode == PermissionMode.ACCEPT_EDITS && kind == AcpSchema.ToolKind.EDIT) {
            return Outcome.ALLOW;
        }

        // Safe-command auto-allow: read-only shell commands (ls, cat, git status, ...) skip the
        // prompt entirely. Mirrors Codex's is_safe_command.rs. Placed after the READ_ONLY reject so
        // strict mode still blocks every shell call.
        if (rawCommand != null && isShellExecutionTool(toolName) && SafeCommand.isKnownSafe(rawCommand)) {
            return Outcome.ALLOW;
        }

        // In-session "Always allow" cache, except for tools where one approval would be carte
        // blanche (currently {@code "shell"}).
        if (!requiresPerCallPrompt(toolName) && isAlwaysAllowed) {
            return Outcome.ALLOW;
        }

        return Outcome.PROMPT;
    }

    private static boolean isReadOnlyKind(AcpSchema.ToolKind kind) {
        return kind == AcpSchema.ToolKind.READ
                || kind == AcpSchema.ToolKind.SEARCH
                || kind == AcpSchema.ToolKind.THINK
                || kind == AcpSchema.ToolKind.FETCH;
    }

    /**
     * Classifies a Brokk tool name into an {@link AcpSchema.ToolKind}. Mirrors the categorization
     * already used by {@link AcpConsoleIO} for {@code tool_call} notifications, so the gate and the
     * UI agree on a tool's nature.
     */
    static AcpSchema.ToolKind classify(String toolName) {
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
}
