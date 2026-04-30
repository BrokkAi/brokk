package ai.brokk.acp;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import org.jetbrains.annotations.Nullable;

/**
 * Brokk-internal extension of {@link SyncPromptContext} that adds tool-aware permission prompts so
 * the agent can offer {@code allow_always}/{@code reject_always} permission options keyed by tool
 * name and short-circuit subsequent prompts for the same tool within a session.
 */
interface AcpPromptContext extends SyncPromptContext {

    /** Outcome of a tool-permission prompt that may include a sandbox-bypass option. */
    enum PermissionDecision {
        /** Allow execution under the default sandbox policy. */
        ALLOW,
        /** Allow execution and bypass sandbox restrictions for this run. */
        ALLOW_NO_SANDBOX,
        /** Deny execution. */
        DENY;

        boolean isApproved() {
            return this != DENY;
        }
    }

    /**
     * Asks the client for permission to execute the given action for {@code toolName}.
     *
     * <p>The implementation may consult a per-session sticky cache; if the user previously chose
     * {@code allow_always}/{@code reject_always} for this tool, the verdict is reused without a
     * round trip. Otherwise the four ACP permission options are presented and any {@code *_always}
     * outcome is remembered.
     */
    boolean askPermission(String action, String toolName);

    /**
     * Asks for permission with optional sandbox-bypass options. Equivalent to {@link
     * #askPermission(String, String)} when {@code offerSandboxBypass} is false.
     *
     * <p>When {@code offerSandboxBypass} is true, the client is shown two extra buttons that map
     * to {@link PermissionDecision#ALLOW_NO_SANDBOX}: a one-shot variant and an "always" variant
     * remembered for the rest of the session under {@code cacheKey}.
     *
     * @param action the human-readable action for the tool-call card
     * @param toolName the tool name (used to classify the kind of permission)
     * @param cacheKey key used for the session sticky cache; for shell tools this should encode the
     *     specific command/task so a session-level approval doesn't blanket-allow other invocations
     * @param offerSandboxBypass whether to offer the {@code allow_no_sandbox*} options
     * @param rawCommand for shell-execution tools, the raw command string the model wants to run.
     *     Enables {@link SafeCommand}'s known-safe auto-approval for read-only commands like
     *     {@code ls}, {@code cat}, or {@code git status}. {@code null} for non-shell tools.
     */
    PermissionDecision askPermissionDetailed(
            String action, String toolName, String cacheKey, boolean offerSandboxBypass, @Nullable String rawCommand);

    @Override
    default boolean askPermission(String action) {
        return askPermission(action, "unknown");
    }
}
