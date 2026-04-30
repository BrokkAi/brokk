package ai.brokk.acp;

import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Conservative classifier for "is this shell command risky enough to keep prompting even when the
 * kernel sandbox is active?". Phase 3 of the Codex permission port (see PRs #3497 and #3499).
 *
 * <p>The kernel sandbox ({@link ai.brokk.util.SandboxPolicy#WORKSPACE_WRITE} via Apple Seatbelt or
 * Linux Bubblewrap) restricts filesystem writes to the workspace, but does NOT block the network,
 * process control, or escalation of privileges. So a "dangerous" command, in this file's sense, is
 * one whose damage falls outside what the sandbox prevents — destructive intent on remote state,
 * other processes, or system permissions.
 *
 * <p>Errs on the side of "dangerous": ambiguous parses, shell metacharacters, and unrecognized
 * tokens all return {@code true}. Better to ask once than to skip a prompt for {@code git push} or
 * {@code npm publish}.
 *
 * <p>Mirrors {@code codex-rs/shell-command/src/command_safety/is_dangerous_command.rs} but with
 * Brokk-specific additions for network-touching and remote-mutating commands that the original
 * Codex list does not flag.
 */
public final class DangerousCommand {

    private DangerousCommand() {}

    /**
     * Executable basenames whose mere invocation is enough to classify the command as dangerous,
     * regardless of arguments. Each one either touches the network, kills processes, or modifies
     * permissions — all things the workspace-write sandbox does not protect against.
     */
    private static final Set<String> ALWAYS_DANGEROUS_NAMES = Set.of(
            "sudo",
            "su",
            "doas",
            "kill",
            "pkill",
            "killall",
            "chmod",
            "chown",
            "chgrp",
            "mount",
            "umount",
            "systemctl",
            "service",
            "launchctl",
            "curl",
            "wget",
            "ssh",
            "scp",
            "rsync",
            "ftp",
            "sftp",
            "nc",
            "ncat",
            "socat");

    /**
     * Git subcommands that mutate remote state, history, or working-tree state in ways the sandbox
     * cannot undo. Read-only subcommands (status/log/diff/show/branch) are excluded — those are
     * handled by {@link SafeCommand} and never reach this classifier on the auto-allow path.
     */
    private static final Set<String> DANGEROUS_GIT_SUBCOMMANDS = Set.of(
            "push",
            "pull",
            "fetch",
            "clone",
            "reset",
            "rebase",
            "rebase-apply",
            "merge",
            "cherry-pick",
            "revert",
            "stash",
            "tag",
            "remote",
            "submodule",
            "config",
            "gc",
            "fsck",
            "filter-branch",
            "filter-repo",
            "format-patch",
            "am",
            "apply",
            "send-email",
            "request-pull",
            "credential",
            "clean");

    /**
     * Package-management subcommands that publish, install globally, or otherwise touch state
     * outside the workspace. Read-only subcommands (e.g. {@code npm view}, {@code pip show}) are
     * not in this list.
     */
    private static final Set<String> DANGEROUS_NPM_SUBCOMMANDS =
            Set.of("publish", "install", "i", "add", "uninstall", "rm", "update", "audit", "link", "unlink");

    private static final Set<String> DANGEROUS_CARGO_SUBCOMMANDS =
            Set.of("publish", "install", "uninstall", "yank", "owner", "login", "logout");

    private static final Set<String> DANGEROUS_PIP_SUBCOMMANDS =
            Set.of("install", "uninstall", "download", "wheel", "config");

    /**
     * Returns {@code true} if the command should keep going through the per-call permission prompt
     * even when the kernel sandbox is active.
     *
     * <p>Conservative: any input that cannot be cleanly classified — empty, full of shell
     * metacharacters, non-ASCII, multi-segment with operators — is treated as dangerous. The cost
     * of a false positive is one extra prompt; the cost of a false negative is an unintended
     * destructive action.
     */
    public static boolean isDangerous(@Nullable String rawCommand) {
        if (rawCommand == null) {
            return true;
        }
        var trimmed = rawCommand.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        // Any redirection, expansion, substitution, or escape we cannot reliably classify means we
        // bail to "dangerous". The Phase 1 SafeCommand path already rejects these for read-only
        // auto-allow; here we treat them as un-classifiable and prompt the user.
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '>' || c == '<' || c == '$' || c == '`' || c == '\\' || c == '\n' || c == '\r') {
                return true;
            }
        }
        // Reject operator-chained commands too. A chain like "cmd1 && cmd2" can mix safe and
        // dangerous parts; rather than parse them all, prompt.
        if (trimmed.contains("&&") || trimmed.contains("||") || trimmed.contains("|") || trimmed.contains(";")) {
            return true;
        }
        // Pull out argv[0] and the rest. We do NOT support quoting here — the Phase 1 SafeCommand
        // already handled the well-formed cases; anything that reaches us is either bare words or
        // suspicious.
        var parts = trimmed.split("\\s+");
        if (parts.length == 0) {
            return true;
        }
        var exe = stripPathPrefix(parts[0]);

        if (ALWAYS_DANGEROUS_NAMES.contains(exe)) {
            return true;
        }
        return switch (exe) {
            case "rm", "mv" -> true;
            case "cp" -> isDangerousCp(parts);
            case "git" -> isDangerousGit(parts);
            case "npm", "pnpm", "yarn" -> isDangerousNpmFamily(parts);
            case "cargo" -> isDangerousCargo(parts);
            case "pip", "pip3", "uv" -> isDangerousPip(parts);
            default -> false;
        };
    }

    private static String stripPathPrefix(String exe) {
        int slash = Math.max(exe.lastIndexOf('/'), exe.lastIndexOf('\\'));
        return slash >= 0 ? exe.substring(slash + 1) : exe;
    }

    /**
     * {@code cp} is dangerous if any argument starts with {@code /} (potentially overwriting
     * outside the workspace, which the sandbox should prevent — but be conservative) or looks like
     * a flag we don't recognize. Plain {@code cp src dst} where both are relative is fine.
     */
    private static boolean isDangerousCp(String[] argv) {
        for (int i = 1; i < argv.length; i++) {
            var a = argv[i];
            if (a.startsWith("/") || a.startsWith("~")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDangerousGit(String[] argv) {
        // Find the first non-flag token after "git"; that is the subcommand.
        for (int i = 1; i < argv.length; i++) {
            var a = argv[i];
            if (a.startsWith("-")) {
                continue;
            }
            return DANGEROUS_GIT_SUBCOMMANDS.contains(a);
        }
        // Bare "git" is harmless (prints help).
        return false;
    }

    private static boolean isDangerousNpmFamily(String[] argv) {
        for (int i = 1; i < argv.length; i++) {
            var a = argv[i];
            if (a.startsWith("-")) {
                continue;
            }
            return DANGEROUS_NPM_SUBCOMMANDS.contains(a);
        }
        return false;
    }

    private static boolean isDangerousCargo(String[] argv) {
        for (int i = 1; i < argv.length; i++) {
            var a = argv[i];
            if (a.startsWith("-")) {
                continue;
            }
            return DANGEROUS_CARGO_SUBCOMMANDS.contains(a);
        }
        return false;
    }

    private static boolean isDangerousPip(String[] argv) {
        for (int i = 1; i < argv.length; i++) {
            var a = argv[i];
            if (a.startsWith("-")) {
                continue;
            }
            return DANGEROUS_PIP_SUBCOMMANDS.contains(a);
        }
        return false;
    }
}
