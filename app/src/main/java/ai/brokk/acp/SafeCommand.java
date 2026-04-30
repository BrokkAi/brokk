package ai.brokk.acp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Pure check for "is this shell command safe enough to auto-approve without a user prompt?".
 * Mirrors {@code codex-rs/shell-command/src/command_safety/is_safe_command.rs} (OpenAI Codex CLI),
 * adapted to Brokk's API where the shell command is a single raw string passed to {@code /bin/sh -c}.
 *
 * <p>The contract is intentionally conservative: any input that cannot be unambiguously decomposed
 * into a list of known-safe subcommands returns {@code false}. Anything containing shell
 * metacharacters we do not explicitly handle (parentheses, braces, redirection, command
 * substitution, environment-variable expansion, escapes) is rejected.
 */
public final class SafeCommand {

    private SafeCommand() {}

    private static final int MAX_BASH_LC_RECURSION_DEPTH = 1;

    private static final Set<String> SIMPLE_SAFE_NAMES = Set.of(
            "cat", "cd", "cut", "echo", "expr", "false", "grep", "head", "id", "ls", "nl", "paste", "pwd", "rev", "seq",
            "stat", "tail", "tr", "true", "uname", "uniq", "wc", "which", "whoami");

    private static final Set<String> SHELL_INTERPRETERS = Set.of("bash", "sh", "zsh");

    private static final Set<String> SHELL_LC_FLAGS = Set.of("-lc", "-c");

    private static final Set<String> UNSAFE_FIND_OPTIONS =
            Set.of("-exec", "-execdir", "-ok", "-okdir", "-delete", "-fls", "-fprint", "-fprint0", "-fprintf");

    private static final Set<String> UNSAFE_RG_OPTIONS_WITH_ARGS = Set.of("--pre", "--hostname-bin");

    private static final Set<String> UNSAFE_RG_OPTIONS_WITHOUT_ARGS = Set.of("--search-zip", "-z");

    private static final Set<String> SAFE_GIT_SUBCOMMANDS = Set.of("status", "log", "diff", "show", "branch");

    private static final Set<String> UNSAFE_GIT_SUBCOMMAND_FLAGS_EXACT =
            Set.of("--output", "--ext-diff", "--textconv", "--exec", "--paginate");

    private static final Set<String> UNSAFE_GIT_GLOBAL_FLAGS_EXACT =
            Set.of("-c", "--config-env", "--git-dir", "--work-tree", "--exec-path", "--namespace", "--super-prefix");

    private static final Set<String> UNSAFE_GIT_GLOBAL_FLAG_PREFIXES = Set.of(
            "-c", "--config-env=", "--git-dir=", "--work-tree=", "--exec-path=", "--namespace=", "--super-prefix=");

    private static final Set<String> SAFE_GIT_BRANCH_FLAGS =
            Set.of("--list", "-l", "--show-current", "-a", "--all", "-r", "--remotes", "-v", "-vv", "--verbose");

    /** Returns {@code true} if the raw shell command is known-safe and may be auto-approved. */
    public static boolean isKnownSafe(String rawCommand) {
        if (rawCommand.isEmpty()) {
            return false;
        }
        return isKnownSafeScript(rawCommand, 0);
    }

    private static boolean isKnownSafeScript(String script, int depth) {
        var segments = splitOnTopLevelOperators(script);
        if (segments.isEmpty()) {
            return false;
        }
        int nonEmpty = 0;
        for (var raw : segments.get()) {
            var trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            nonEmpty++;
            var argv = tokenize(trimmed);
            if (argv.isEmpty() || argv.get().isEmpty()) {
                return false;
            }
            if (!isKnownSafeArgv(argv.get(), depth)) {
                return false;
            }
        }
        return nonEmpty > 0;
    }

    private static boolean isKnownSafeArgv(List<String> argv, int depth) {
        if (argv.size() == 3
                && SHELL_INTERPRETERS.contains(argv.get(0))
                && SHELL_LC_FLAGS.contains(argv.get(1))
                && depth < MAX_BASH_LC_RECURSION_DEPTH) {
            return isKnownSafeScript(argv.get(2), depth + 1);
        }
        return isSafeToCallWithExec(argv);
    }

    private static boolean isSafeToCallWithExec(List<String> argv) {
        var name = executableLookupKey(argv.get(0));
        if (SIMPLE_SAFE_NAMES.contains(name)) {
            return true;
        }
        return switch (name) {
            case "base64" -> isSafeBase64(argv);
            case "find" -> isSafeFind(argv);
            case "rg" -> isSafeRipgrep(argv);
            case "git" -> isSafeGit(argv);
            case "sed" -> isSafeSed(argv);
            default -> false;
        };
    }

    private static String executableLookupKey(String executable) {
        int slash = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
        return slash >= 0 ? executable.substring(slash + 1) : executable;
    }

    private static boolean isSafeBase64(List<String> argv) {
        for (int i = 1; i < argv.size(); i++) {
            var arg = argv.get(i);
            if (arg.equals("-o")
                    || arg.equals("--output")
                    || arg.startsWith("--output=")
                    || (arg.startsWith("-o") && !arg.equals("-o"))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafeFind(List<String> argv) {
        return argv.stream().skip(1).noneMatch(UNSAFE_FIND_OPTIONS::contains);
    }

    private static boolean isSafeRipgrep(List<String> argv) {
        for (int i = 1; i < argv.size(); i++) {
            var arg = argv.get(i);
            if (UNSAFE_RG_OPTIONS_WITHOUT_ARGS.contains(arg)) {
                return false;
            }
            for (var opt : UNSAFE_RG_OPTIONS_WITH_ARGS) {
                if (arg.equals(opt) || arg.startsWith(opt + "=")) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isSafeGit(List<String> argv) {
        if (gitHasUnsafeGlobalOption(argv)) {
            return false;
        }
        int subIdx = findGitSubcommandIndex(argv);
        if (subIdx < 0 || subIdx >= argv.size()) {
            return false;
        }
        var subcommand = argv.get(subIdx);
        if (!SAFE_GIT_SUBCOMMANDS.contains(subcommand)) {
            return false;
        }
        var subArgs = argv.subList(subIdx + 1, argv.size());
        if (!gitSubcommandArgsAreReadOnly(subArgs)) {
            return false;
        }
        if (subcommand.equals("branch") && !gitBranchIsReadOnly(subArgs)) {
            return false;
        }
        return true;
    }

    private static boolean gitHasUnsafeGlobalOption(List<String> argv) {
        for (int i = 1; i < argv.size(); i++) {
            if (gitGlobalOptionRequiresPrompt(argv.get(i))) {
                return true;
            }
            if (!argv.get(i).startsWith("-")) {
                return false;
            }
        }
        return false;
    }

    private static boolean gitGlobalOptionRequiresPrompt(String arg) {
        if (UNSAFE_GIT_GLOBAL_FLAGS_EXACT.contains(arg)) {
            return true;
        }
        for (var prefix : UNSAFE_GIT_GLOBAL_FLAG_PREFIXES) {
            if (arg.startsWith(prefix)
                    && !arg.equals(prefix.endsWith("=") ? prefix.substring(0, prefix.length() - 1) : prefix)) {
                return true;
            }
        }
        return false;
    }

    private static int findGitSubcommandIndex(List<String> argv) {
        int i = 1;
        while (i < argv.size()) {
            var arg = argv.get(i);
            if (!arg.startsWith("-")) {
                return i;
            }
            // Known safe global option: -C <path>
            if (arg.equals("-C") || arg.equals("--no-pager")) {
                if (arg.equals("-C")) {
                    i += 2;
                } else {
                    i += 1;
                }
                continue;
            }
            return -1;
        }
        return -1;
    }

    private static boolean gitSubcommandArgsAreReadOnly(List<String> args) {
        for (var arg : args) {
            if (UNSAFE_GIT_SUBCOMMAND_FLAGS_EXACT.contains(arg)
                    || arg.startsWith("--output=")
                    || arg.startsWith("--exec=")) {
                return false;
            }
        }
        return true;
    }

    private static boolean gitBranchIsReadOnly(List<String> branchArgs) {
        if (branchArgs.isEmpty()) {
            return true;
        }
        boolean sawReadOnlyFlag = false;
        for (var arg : branchArgs) {
            if (SAFE_GIT_BRANCH_FLAGS.contains(arg) || arg.startsWith("--format=")) {
                sawReadOnlyFlag = true;
            } else {
                return false;
            }
        }
        return sawReadOnlyFlag;
    }

    private static boolean isSafeSed(List<String> argv) {
        if (argv.size() > 4 || argv.size() < 3) {
            return false;
        }
        if (!"-n".equals(argv.get(1))) {
            return false;
        }
        return isValidSedNArg(argv.get(2));
    }

    private static boolean isValidSedNArg(String arg) {
        if (!arg.endsWith("p")) {
            return false;
        }
        var core = arg.substring(0, arg.length() - 1);
        var parts = core.split(",", -1);
        if (parts.length < 1 || parts.length > 2) {
            return false;
        }
        for (var part : parts) {
            if (part.isEmpty()) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Splits a shell command on top-level boolean and pipe operators ({@code &&}, {@code ||},
     * {@code ;}, {@code |}) while preserving single- and double-quoted regions verbatim. Returns
     * {@link Optional#empty()} if the command contains shell constructs not in our allow-list
     * (subshells, redirection, command substitution, escapes, background {@code &}).
     */
    private static Optional<List<String>> splitOnTopLevelOperators(String command) {
        var result = new ArrayList<String>();
        int len = command.length();
        int start = 0;
        int i = 0;
        while (i < len) {
            char c = command.charAt(i);
            if (c == '\'') {
                int close = command.indexOf('\'', i + 1);
                if (close < 0) {
                    return Optional.empty();
                }
                i = close + 1;
                continue;
            }
            if (c == '"') {
                int j = i + 1;
                while (j < len && command.charAt(j) != '"') {
                    char cc = command.charAt(j);
                    if (cc == '\\' || cc == '$' || cc == '`') {
                        return Optional.empty();
                    }
                    j++;
                }
                if (j >= len) {
                    return Optional.empty();
                }
                i = j + 1;
                continue;
            }
            if (c == '$' || c == '`' || c == '(' || c == ')' || c == '{' || c == '}' || c == '<' || c == '>'
                    || c == '\n' || c == '\r' || c == '\\') {
                return Optional.empty();
            }
            if (c == '&') {
                if (i + 1 < len && command.charAt(i + 1) == '&') {
                    result.add(command.substring(start, i));
                    i += 2;
                    start = i;
                    continue;
                }
                return Optional.empty();
            }
            if (c == '|') {
                if (i + 1 < len && command.charAt(i + 1) == '|') {
                    result.add(command.substring(start, i));
                    i += 2;
                } else {
                    result.add(command.substring(start, i));
                    i += 1;
                }
                start = i;
                continue;
            }
            if (c == ';') {
                result.add(command.substring(start, i));
                i += 1;
                start = i;
                continue;
            }
            i++;
        }
        result.add(command.substring(start));
        return Optional.of(result);
    }

    /**
     * Tokenizes a single command segment (no top-level operators) into argv. Each token is either
     * a bareword, a single-quoted literal, or a double-quoted literal. Mixed-quoting tokens
     * (e.g. {@code abc'def'ghi}) are rejected. Any escape, expansion, or redirection metacharacter
     * yields {@link Optional#empty()}.
     */
    private static Optional<List<String>> tokenize(String segment) {
        var result = new ArrayList<String>();
        int len = segment.length();
        int i = 0;
        while (i < len) {
            char c = segment.charAt(i);
            if (c == ' ' || c == '\t') {
                i++;
                continue;
            }
            if (c == '\n' || c == '\r') {
                return Optional.empty();
            }
            if (c == '\'') {
                int close = segment.indexOf('\'', i + 1);
                if (close < 0) {
                    return Optional.empty();
                }
                if (close + 1 < len && !isTokenBoundary(segment.charAt(close + 1))) {
                    return Optional.empty();
                }
                result.add(segment.substring(i + 1, close));
                i = close + 1;
                continue;
            }
            if (c == '"') {
                int j = i + 1;
                while (j < len && segment.charAt(j) != '"') {
                    char cc = segment.charAt(j);
                    if (cc == '\\' || cc == '$' || cc == '`') {
                        return Optional.empty();
                    }
                    j++;
                }
                if (j >= len) {
                    return Optional.empty();
                }
                if (j + 1 < len && !isTokenBoundary(segment.charAt(j + 1))) {
                    return Optional.empty();
                }
                result.add(segment.substring(i + 1, j));
                i = j + 1;
                continue;
            }
            int wordStart = i;
            while (i < len) {
                char cc = segment.charAt(i);
                if (cc == ' ' || cc == '\t') {
                    break;
                }
                if (cc == '\''
                        || cc == '"'
                        || cc == '$'
                        || cc == '`'
                        || cc == '('
                        || cc == ')'
                        || cc == '{'
                        || cc == '}'
                        || cc == '<'
                        || cc == '>'
                        || cc == '|'
                        || cc == '&'
                        || cc == ';'
                        || cc == '\\'
                        || cc == '\n'
                        || cc == '\r') {
                    return Optional.empty();
                }
                i++;
            }
            result.add(segment.substring(wordStart, i));
        }
        return Optional.of(result);
    }

    private static boolean isTokenBoundary(char c) {
        return c == ' ' || c == '\t';
    }
}
