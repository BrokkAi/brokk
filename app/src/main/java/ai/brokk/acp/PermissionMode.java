package ai.brokk.acp;

import java.util.Optional;

/**
 * Per-session permission policy. Mirrors {@code claude-agent-acp}'s four reference modes and
 * Brokk's Rust ACP server (see {@code brokk-acp-rust/src/session.rs:54-83}); the Java agent
 * surfaces it to clients as a {@code SessionConfigOption} dropdown independent of {@code SessionMode}.
 *
 * <p>{@link #READ_ONLY} is renamed from the reference's "plan" to avoid colliding with Brokk's PLAN
 * behavior mode (LUTZ/CODE/ASK/PLAN), which is a separate dropdown.
 */
enum PermissionMode {
    DEFAULT("default"),
    ACCEPT_EDITS("acceptEdits"),
    READ_ONLY("readOnly"),
    BYPASS_PERMISSIONS("bypassPermissions");

    private final String wireId;

    PermissionMode(String wireId) {
        this.wireId = wireId;
    }

    /** Wire id, matching {@code session/set_config_option} value strings the Rust server uses. */
    String asString() {
        return wireId;
    }

    static Optional<PermissionMode> parse(String s) {
        for (var mode : values()) {
            if (mode.wireId.equals(s)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
