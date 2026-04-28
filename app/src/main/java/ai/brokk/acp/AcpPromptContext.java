package ai.brokk.acp;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;

/**
 * Brokk-internal extension of {@link SyncPromptContext} that adds tool-aware permission prompts so
 * the agent can offer {@code allow_always}/{@code reject_always} permission options keyed by tool
 * name and short-circuit subsequent prompts for the same tool within a session.
 */
interface AcpPromptContext extends SyncPromptContext {

    /**
     * Asks the client for permission to execute the given action for {@code toolName}.
     *
     * <p>The implementation may consult a per-session sticky cache; if the user previously chose
     * {@code allow_always}/{@code reject_always} for this tool, the verdict is reused without a
     * round trip. Otherwise the four ACP permission options are presented and any {@code *_always}
     * outcome is remembered.
     */
    boolean askPermission(String action, String toolName);

    @Override
    default boolean askPermission(String action) {
        return askPermission(action, "unknown");
    }
}
