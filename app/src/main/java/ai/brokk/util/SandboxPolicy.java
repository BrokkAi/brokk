package ai.brokk.util;

/**
 * Controls how shell commands are sandboxed at the OS level.
 *
 * <ul>
 *   <li>{@link #NONE} – no sandboxing; command runs with full privileges.</li>
 *   <li>{@link #READ_ONLY} – kernel-enforced read-only filesystem access (no writes anywhere).</li>
 *   <li>{@link #WORKSPACE_WRITE} – read-only everywhere except the project workspace root,
 *       where writes are allowed.</li>
 * </ul>
 *
 * The actual enforcement mechanism is platform-specific:
 * macOS uses Apple Seatbelt ({@code sandbox-exec}),
 * Linux uses Bubblewrap ({@code bwrap}).
 */
public enum SandboxPolicy {
    /** No sandboxing – full filesystem access. */
    NONE,

    /** Kernel-enforced read-only access. Writes are blocked everywhere. */
    READ_ONLY,

    /** Read-only everywhere except the project workspace root (writable). */
    WORKSPACE_WRITE;

    /** Returns true if any sandboxing is active (not NONE). */
    public boolean isSandboxed() {
        return this != NONE;
    }

    /** Returns true if writes are allowed within the workspace root. */
    public boolean allowsWorkspaceWrites() {
        return this == WORKSPACE_WRITE;
    }
}
