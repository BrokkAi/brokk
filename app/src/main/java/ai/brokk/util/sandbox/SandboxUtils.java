package ai.brokk.util.sandbox;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SandboxUtils {
    public static final List<String> DANGEROUS_FILES = List.of(
            ".gitconfig",
            ".gitmodules",
            ".bashrc",
            ".bash_profile",
            ".zshrc",
            ".zprofile",
            ".profile",
            ".ripgreprc",
            ".mcp.json");

    public static final List<String> DANGEROUS_DIRECTORIES = List.of(".git", ".vscode", ".idea");

    private SandboxUtils() {}

    public static List<String> getDangerousDirectories() {
        List<String> dirs = new ArrayList<>();
        for (String d : DANGEROUS_DIRECTORIES) {
            if (!".git".equals(d)) {
                dirs.add(d);
            }
        }
        dirs.add(".claude/commands");
        dirs.add(".claude/agents");
        return List.copyOf(dirs);
    }

    public static String normalizeCaseForComparison(String path) {
        return path.toLowerCase(Locale.ROOT);
    }

    public static boolean containsGlobChars(String pathPattern) {
        return pathPattern.indexOf('*') >= 0
                || pathPattern.indexOf('?') >= 0
                || pathPattern.indexOf('[') >= 0
                || pathPattern.indexOf(']') >= 0;
    }

    public static String removeTrailingGlobSuffix(String pathPattern) {
        if (pathPattern.endsWith("/**")) {
            return pathPattern.substring(0, pathPattern.length() - 3);
        }
        return pathPattern;
    }

    public static boolean isSymlinkOutsideBoundary(String originalPath, String resolvedPath) {
        String normalizedOriginal = normalizePathString(originalPath);
        String normalizedResolved = normalizePathString(resolvedPath);

        if (normalizedResolved.equals(normalizedOriginal)) {
            return false;
        }

        if (normalizedOriginal.startsWith("/tmp/") && normalizedResolved.equals("/private" + normalizedOriginal)) {
            return false;
        }
        if (normalizedOriginal.startsWith("/var/") && normalizedResolved.equals("/private" + normalizedOriginal)) {
            return false;
        }
        if ("/".equals(normalizedResolved)) {
            return true;
        }

        int resolvedParts = countPathParts(normalizedResolved);
        if (resolvedParts <= 1) {
            return true;
        }

        if (normalizedOriginal.startsWith(normalizedResolved + "/")) {
            return true;
        }

        String canonicalOriginal = normalizedOriginal;
        if (normalizedOriginal.startsWith("/tmp/")) {
            canonicalOriginal = "/private" + normalizedOriginal;
        } else if (normalizedOriginal.startsWith("/var/")) {
            canonicalOriginal = "/private" + normalizedOriginal;
        }

        if (!canonicalOriginal.equals(normalizedOriginal) && canonicalOriginal.startsWith(normalizedResolved + "/")) {
            return true;
        }

        boolean resolvedStartsWithOriginal = normalizedResolved.startsWith(normalizedOriginal + "/");
        boolean resolvedStartsWithCanonical =
                !canonicalOriginal.equals(normalizedOriginal) && normalizedResolved.startsWith(canonicalOriginal + "/");
        boolean resolvedIsCanonical =
                !canonicalOriginal.equals(normalizedOriginal) && normalizedResolved.equals(canonicalOriginal);

        if (!resolvedIsCanonical && !resolvedStartsWithOriginal && !resolvedStartsWithCanonical) {
            return true;
        }

        return false;
    }

    public static String normalizePathForSandbox(String pathPattern) {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        String normalizedPath = pathPattern;

        String homeDir = System.getProperty("user.home");

        if ("~".equals(pathPattern)) {
            normalizedPath = homeDir;
        } else if (pathPattern.startsWith("~/")) {
            normalizedPath = homeDir + pathPattern.substring(1);
        } else if (pathPattern.startsWith("./") || pathPattern.startsWith("../")) {
            normalizedPath = cwd.resolve(pathPattern).normalize().toString();
        } else if (!Paths.get(pathPattern).isAbsolute()) {
            normalizedPath = cwd.resolve(pathPattern).normalize().toString();
        }

        if (containsGlobChars(normalizedPath)) {
            String staticPrefix = extractStaticPrefix(normalizedPath);
            if (!staticPrefix.isEmpty() && !"/".equals(staticPrefix)) {
                String baseDir = staticPrefix.endsWith("/")
                        ? staticPrefix.substring(0, staticPrefix.length() - 1)
                        : safeDirname(staticPrefix);

                if (!baseDir.isEmpty()) {
                    try {
                        Path resolvedBaseDir = Paths.get(baseDir).toRealPath(LinkOption.NOFOLLOW_LINKS);
                        String resolvedBaseDirStr = normalizePathString(resolvedBaseDir.toString());
                        if (!isSymlinkOutsideBoundary(baseDir, resolvedBaseDirStr)) {
                            String patternSuffix = normalizedPath.substring(baseDir.length());
                            return resolvedBaseDirStr + patternSuffix;
                        }
                    } catch (IOException | RuntimeException ignored) {
                        // Resolution failures are expected (missing paths, permissions, etc.); fall back to original.
                    }
                }
            }
            return normalizedPath;
        }

        try {
            Path real = Paths.get(normalizedPath).toRealPath();
            String realStr = normalizePathString(real.toString());
            if (!isSymlinkOutsideBoundary(normalizedPath, realStr)) {
                normalizedPath = realStr;
            }
        } catch (IOException | RuntimeException ignored) {
            // Resolution failures are expected (missing paths, permissions, etc.); fall back to normalized path.
        }

        return normalizedPath;
    }

    public static List<String> getDefaultWritePaths() {
        String homeDir = System.getProperty("user.home");
        return List.of(
                "/dev/stdout",
                "/dev/stderr",
                "/dev/null",
                "/dev/tty",
                "/dev/dtracehelper",
                "/dev/autofs_nowait",
                "/tmp/claude",
                "/private/tmp/claude",
                homeDir + "/.npm/_logs",
                homeDir + "/.claude/debug");
    }

    private static String normalizePathString(String p) {
        Path normalized = Paths.get(p).normalize();
        String s = normalized.toString();
        FileSystem fs = FileSystems.getDefault();
        String sep = fs.getSeparator();
        if (!"/".equals(sep)) {
            s = s.replace(sep, "/");
        }
        return s;
    }

    private static int countPathParts(String normalizedPath) {
        String s = normalizedPath;
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/") && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return 0;
        }
        int parts = 0;
        int i = 0;
        while (i < s.length()) {
            int next = s.indexOf('/', i);
            parts++;
            if (next < 0) {
                break;
            }
            i = next + 1;
        }
        return parts;
    }

    private static String extractStaticPrefix(String normalizedPath) {
        int idx = firstGlobCharIndex(normalizedPath);
        if (idx < 0) {
            return normalizedPath;
        }
        return normalizedPath.substring(0, idx);
    }

    private static int firstGlobCharIndex(String s) {
        int a = s.indexOf('*');
        int b = s.indexOf('?');
        int c = s.indexOf('[');
        int d = s.indexOf(']');

        int min = Integer.MAX_VALUE;
        if (a >= 0) min = Math.min(min, a);
        if (b >= 0) min = Math.min(min, b);
        if (c >= 0) min = Math.min(min, c);
        if (d >= 0) min = Math.min(min, d);

        return min == Integer.MAX_VALUE ? -1 : min;
    }

    private static String safeDirname(String prefix) {
        int lastSlash = prefix.lastIndexOf('/');
        if (lastSlash < 0) {
            return "";
        }
        if (lastSlash == 0) {
            return "/";
        }
        return prefix.substring(0, lastSlash);
    }
}
