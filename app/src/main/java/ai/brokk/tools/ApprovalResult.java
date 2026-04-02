package ai.brokk.tools;

/**
 * Result of an approval check before tool execution.
 *
 * @see ai.brokk.IConsoleIO#beforeToolCall
 */
public enum ApprovalResult {
    /** Tool execution is approved. */
    APPROVED,

    /** Tool execution was denied by the user. */
    DENIED;

    /** Returns true if the tool execution is approved. */
    public boolean isApproved() {
        return this == APPROVED;
    }
}
