package ai.brokk.util.sandbox.macos;

import ai.brokk.util.sandbox.SandboxConfig;
import ai.brokk.util.sandbox.SandboxUtils;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class MacosSandbox {
    private MacosSandbox() {}

    public static String globToRegex(String globPattern) {
        if (globPattern == null) {
            throw new IllegalArgumentException("globPattern must not be null");
        }

        String s = globPattern;

        s = s.replaceAll("([.\\^\\$\\+\\{\\}\\(\\)\\|\\\\])", "\\\\$1");
        s = s.replaceAll("\\[([^\\]]*?)$", "\\\\[$1");

        s = s.replace("**/", "__GLOBSTAR_SLASH__");
        s = s.replace("**", "__GLOBSTAR__");
        s = s.replace("*", "[^/]*");
        s = s.replace("?", "[^/]");
        s = s.replace("__GLOBSTAR_SLASH__", "(.*/)?");
        s = s.replace("__GLOBSTAR__", ".*");

        return "^" + s + "$";
    }

    public static List<String> getMandatoryDenyPatterns(boolean allowGitConfig) {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Set<String> deny = new LinkedHashSet<>();

        for (String fileName : SandboxUtils.DANGEROUS_FILES) {
            deny.add(cwd.resolve(fileName).toString());
            deny.add("**/" + fileName);
        }

        for (String dirName : SandboxUtils.getDangerousDirectories()) {
            deny.add(cwd.resolve(dirName).toString());
            deny.add("**/" + dirName + "/**");
        }

        deny.add(cwd.resolve(".git/hooks").toString());
        deny.add("**/.git/hooks/**");

        if (!allowGitConfig) {
            deny.add(cwd.resolve(".git/config").toString());
            deny.add("**/.git/config");
        }

        return List.copyOf(deny);
    }

    private static String escapePath(String s) {
        if (s == null) {
            throw new IllegalArgumentException("path must not be null");
        }

        StringBuilder out = new StringBuilder(s.length() + 2);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch <= 0x1F) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    private static String shellSingleQuote(String s) {
        if (s == null) {
            throw new IllegalArgumentException("string must not be null");
        }
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private static List<String> generateReadRules(List<String> denyRead) {
        if (denyRead == null || denyRead.isEmpty()) {
            return List.of("(allow file-read*)");
        }

        List<String> rules = new ArrayList<>();
        rules.add("(allow file-read*)");

        for (String pathPattern : denyRead) {
            if (pathPattern == null || pathPattern.isBlank()) {
                continue;
            }
            String normalized = SandboxUtils.normalizePathForSandbox(pathPattern);

            if (SandboxUtils.containsGlobChars(normalized)) {
                String regex = globToRegex(normalized);
                rules.add("(deny file-read*");
                rules.add("  (regex " + escapePath(regex) + ")");
                rules.add(")");
            } else {
                rules.add("(deny file-read*");
                rules.add("  (subpath " + escapePath(normalized) + ")");
                rules.add(")");
            }
        }

        return List.copyOf(rules);
    }

    private static List<String> generateWriteRules(
            List<String> allowWrite, List<String> denyWrite, boolean allowGitConfig) {
        boolean hasAllow = allowWrite != null && !allowWrite.isEmpty();
        boolean hasDeny = denyWrite != null && !denyWrite.isEmpty();
        if (!hasAllow && !hasDeny) {
            return List.of("(allow file-write*)");
        }

        List<String> rules = new ArrayList<>();

        if (hasAllow) {
            for (String pathPattern : allowWrite) {
                if (pathPattern == null || pathPattern.isBlank()) {
                    continue;
                }
                String normalized = SandboxUtils.normalizePathForSandbox(pathPattern);

                if (SandboxUtils.containsGlobChars(normalized)) {
                    String regex = globToRegex(normalized);
                    rules.add("(allow file-write*");
                    rules.add("  (regex " + escapePath(regex) + ")");
                    rules.add(")");
                } else {
                    rules.add("(allow file-write*");
                    rules.add("  (subpath " + escapePath(normalized) + ")");
                    rules.add(")");
                }
            }
        }

        List<String> combinedDenies = new ArrayList<>();
        if (denyWrite != null) {
            combinedDenies.addAll(denyWrite);
        }
        combinedDenies.addAll(getMandatoryDenyPatterns(allowGitConfig));

        for (String pathPattern : combinedDenies) {
            if (pathPattern == null || pathPattern.isBlank()) {
                continue;
            }
            String normalized = SandboxUtils.normalizePathForSandbox(pathPattern);

            if (SandboxUtils.containsGlobChars(normalized)) {
                String regex = globToRegex(normalized);
                rules.add("(deny file-write*");
                rules.add("  (regex " + escapePath(regex) + ")");
                rules.add(")");
            } else {
                rules.add("(deny file-write*");
                rules.add("  (subpath " + escapePath(normalized) + ")");
                rules.add(")");
            }
        }

        return List.copyOf(rules);
    }

    public static String generateSandboxProfile(SandboxConfig.FilesystemConfig fsConfig, boolean allowGitConfig) {
        Objects.requireNonNull(fsConfig, "fsConfig");

        List<String> profile = new ArrayList<>();

        profile.add("(version 1)");
        profile.add("(deny default)");
        profile.add("");
        profile.add("; Essential permissions - based on Chrome sandbox policy");
        profile.add("; Process permissions");
        profile.add("(allow process-exec)");
        profile.add("(allow process-fork)");
        profile.add("(allow process-info* (target same-sandbox))");
        profile.add("(allow signal (target same-sandbox))");
        profile.add("(allow mach-priv-task-port (target same-sandbox))");
        profile.add("");
        profile.add("; File I/O on device files");
        profile.add("(allow file-ioctl (literal \"/dev/null\"))");
        profile.add("(allow file-ioctl (literal \"/dev/zero\"))");
        profile.add("(allow file-ioctl (literal \"/dev/random\"))");
        profile.add("(allow file-ioctl (literal \"/dev/urandom\"))");
        profile.add("(allow file-ioctl (literal \"/dev/dtracehelper\"))");
        profile.add("(allow file-ioctl (literal \"/dev/tty\"))");
        profile.add("");
        profile.add("(allow file-ioctl file-read-data file-write-data");
        profile.add("  (require-all");
        profile.add("    (literal \"/dev/null\")");
        profile.add("    (vnode-type CHARACTER-DEVICE)");
        profile.add("  )");
        profile.add(")");
        profile.add("");
        profile.add("; Network");
        profile.add("(allow network*)");
        profile.add("");
        profile.add("; File read");
        profile.addAll(generateReadRules(fsConfig.denyRead()));
        profile.add("");
        profile.add("; File write");
        profile.addAll(generateWriteRules(fsConfig.allowWrite(), fsConfig.denyWrite(), allowGitConfig));

        return String.join("\n", profile);
    }

    public static String wrapCommand(
            String command, SandboxConfig.FilesystemConfig fsConfig, boolean allowGitConfig, String shellPath) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must not be null/blank");
        }
        Objects.requireNonNull(fsConfig, "fsConfig");

        String shell = (shellPath == null || shellPath.isBlank()) ? "/bin/bash" : shellPath;
        String profile = generateSandboxProfile(fsConfig, allowGitConfig);

        return "sandbox-exec -p " + shellSingleQuote(profile) + " " + shell + " -c " + shellSingleQuote(command);
    }
}
