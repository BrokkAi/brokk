package ai.brokk.tools;

/**
 * Result of an approval check before tool execution.
 *
 * @see ai.brokk.IConsoleIO#beforeToolCall
 */
public enum ApprovalResult {
    /** Tool execution is approved. */
    APPROVED,

    /** Tool execution is approved and sandbox restrictions should be skipped. */
    APPROVED_NO_SANDBOX,

    /** Tool execution was denied by the user. */
    DENIED;

    /** Returns true if the tool execution is approved (with or without sandbox). */
    public boolean isApproved() {
        return this == APPROVED || this == APPROVED_NO_SANDBOX;
    }

    /** Returns true if sandbox restrictions should be bypassed for this execution. */
    public boolean skipSandbox() {
        return this == APPROVED_NO_SANDBOX;
    }
}
