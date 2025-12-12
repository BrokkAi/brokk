package ai.brokk.util;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Utility for canonicalizing path-like strings into an OS-agnostic, stable on-disk representation.
 *
 * Canonicalization rules:
 * - Use forward slashes ('/') as separators (always).
 * - Strip leading "./" segments (repeatedly) for non-absolute paths.
 * - Remove a single trailing '/' for non-root results.
 * - Collapse "." and ".." segments.
 * - If a path is absolute and located under the given project root, convert it to a project-relative path.
 * - If a path is absolute but is outside the project root, keep it absolute (with forward slashes).
 *
 * Environment-variable path values (e.g., JAVA_HOME) should use
 * {@link #canonicalizeEnvPathValue(String)} which does not force relativity and preserves UNC forms.
 *
 * Notes:
 * - This operates on Strings, not {@link java.nio.file.Path} objects, to avoid
 *   platform-specific re-interpretation of non-native absolute forms (e.g., "C:/..." on Linux).
 * - The output is intended for persistence (e.g., in project.properties), not for filesystem I/O.
 */
public final class PathNormalizer {

    private PathNormalizer() {}

    /**
     * Canonicalize a single path-like string for persistence relative to a project.
     *
     * @param raw         input path as provided by user/settings; may be relative or absolute
     * @param projectRoot the canonical absolute project root to relativize against when {@code raw} is absolute
     * @return canonical String using forward slashes, or empty string if {@code raw} is blank
     */
    public static String canonicalizeForProject(String raw, Path projectRoot) {
        String s = raw.trim();
        if (s.isEmpty()) return "";

        // Normalize separators first: backslashes -> forward slashes
        s = s.replace('\\', '/');

        // Strip leading "./" repeatedly for non-absolute values
        if (!looksAbsolute(s)) {
            while (s.startsWith("./")) {
                s = s.substring(2);
            }
        }

        // Collapse segments, preserving UNC leading '//' if present and drive prefixes like 'C:'
        s = collapseSegmentsPreservingPrefixes(s);

        // Remove single trailing '/' for non-root values
        if (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }

        // Try to relativize if absolute and under projectRoot
        if (looksAbsolute(s)) {
            String maybeRel = tryRelativizeAgainstProject(s, projectRoot);
            if (maybeRel != null) {
                s = maybeRel;
                // After relativization, ensure we still adhere to canonical forms
                // (strip any leading "./" that might have been introduced).
                while (s.startsWith("./")) {
                    s = s.substring(2);
                }
                if (s.length() > 1 && s.endsWith("/")) {
                    s = s.substring(0, s.length() - 1);
                }
            }
        }

        return s;
    }

    /**
     * Canonicalize a collection of path-like strings for persistence relative to a project.
     * Blank/empty entries are dropped. Duplicates are removed with stable insertion order.
     *
     * @param raws        collection of raw path strings
     * @param projectRoot project root for potential relativization
     * @return a LinkedHashSet containing canonicalized entries
     */
    public static Set<String> canonicalizeAllForProject(Collection<String> raws, Path projectRoot) {
        Set<String> result = new LinkedHashSet<>();
        for (String r : raws) {
            String c = canonicalizeForProject(r, projectRoot);
            if (!c.isBlank()) {
                result.add(c);
            }
        }
        return result;
    }

    /**
     * Canonicalize an environment path value (e.g., JAVA_HOME) in a conservative manner:
     * - Convert backslashes to forward slashes.
     * - Trim whitespace.
     * - Collapse "." and ".." segments where safe.
     * - Preserve UNC leading '//' forms if present.
     * - Do not force relativity even if under the project root.
     *
     * @param raw input environment path value
     * @return canonicalized path string, or empty string if blank/null
     */
    public static String canonicalizeEnvPathValue(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) return "";

        s = s.replace('\\', '/');
        s = collapseSegmentsPreservingPrefixes(s);

        // Avoid trailing slash except for root-like values ("/", "C:/", "//server/share")
        if (s.length() > 1 && s.endsWith("/")) {
            // Keep "C:/" as-is if it's exactly a drive root
            if (!isDriveRoot(s) && !isNetworkShareRoot(s)) {
                s = s.substring(0, s.length() - 1);
            }
        }
        return s;
    }

    // ------------------------------
    // Internal helpers
    // ------------------------------

    private static boolean looksAbsolute(String s) {
        if (s.startsWith("/")) return true; // POSIX absolute
        if (s.startsWith("//")) return true; // UNC-like
        // Windows drive-absolute in forward-slash form: "C:/..."
        return s.matches("^[A-Za-z]:/.*");
    }

    private static boolean isDriveRoot(String s) {
        // "C:/"
        return s.matches("^[A-Za-z]:/$");
    }

    private static boolean isNetworkShareRoot(String s) {
        // minimal UNC share root e.g. "//server/share"
        if (!s.startsWith("//")) return false;
        String remainder = s.substring(2);
        int first = remainder.indexOf('/');
        if (first < 0) return false;
        int second = remainder.indexOf('/', first + 1);
        return second < 0; // exactly two segments after '//'
    }

    /**
     * Collapse '.' and '..' while preserving:
     * - drive prefixes like "C:"
     * - leading double-slash UNC forms
     */
    private static String collapseSegmentsPreservingPrefixes(String s) {
        boolean hasDrivePrefix = s.matches("^[A-Za-z]:.*");
        String drivePrefix = "";
        if (hasDrivePrefix) {
            drivePrefix = s.substring(0, 2); // "C:"
            s = s.substring(2);
        }

        boolean preserveDoubleSlash = s.startsWith("//");
        boolean absolute = s.startsWith("/") || preserveDoubleSlash;

        // Strip leading slashes from the body we'll segment (we will restore later)
        while (s.startsWith("/")) {
            s = s.substring(1);
        }

        // Segment and collapse
        String[] parts = s.split("/", -1);
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty() && !"..".equals(stack.peekLast())) {
                    stack.pollLast();
                } else {
                    // Keep leading ".." only for relative paths
                    if (!absolute && drivePrefix.isEmpty()) {
                        stack.addLast("..");
                    }
                }
            } else {
                stack.addLast(part);
            }
        }

        StringBuilder out = new StringBuilder();
        if (!drivePrefix.isEmpty()) {
            out.append(drivePrefix);
            out.append('/');
        } else if (preserveDoubleSlash) {
            out.append("//");
        } else if (absolute) {
            out.append('/');
        }

        boolean first = true;
        for (String seg : stack) {
            if (!first) out.append('/');
            out.append(seg);
            first = false;
        }

        // If nothing remained and it was absolute or drive-rooted, return just the root prefix
        if (stack.isEmpty()) {
            if (!drivePrefix.isEmpty()) return drivePrefix + "/";
            if (absolute) return "/";
        }

        return out.toString();
    }

    /**
     * Attempt to relativize an absolute forward-slash path string against an actual project root.
     * If the given path cannot be parsed as an absolute path on this OS, or is outside the project root,
     * return null. Otherwise return a forward-slash relative path.
     */
    @Nullable
    private static String tryRelativizeAgainstProject(String absForwardSlashPath, Path projectRoot) {
        try {
            // Convert absForwardSlashPath into a system path best-effort for comparison.
            Path asSystemPath = toSystemPath(absForwardSlashPath);
            if (asSystemPath == null || !asSystemPath.isAbsolute()) {
                return null;
            }

            // Use toRealPath() when possible to resolve canonical paths.
            // Fall back to normalize() if file doesn't exist or toRealPath() fails.
            Path projectAbs;
            Path norm;
            try {
                projectAbs = projectRoot.toRealPath();
                norm = asSystemPath.toRealPath();
            } catch (Exception e) {
                projectAbs = projectRoot.toAbsolutePath().normalize();
                norm = asSystemPath.normalize();
            }

            if (norm.startsWith(projectAbs)) {
                Path rel = projectAbs.relativize(norm);
                // Ensure forward slashes
                String relStr = rel.toString().replace('\\', '/');
                // Sanitize "./" and trailing '/'
                while (relStr.startsWith("./")) {
                    relStr = relStr.substring(2);
                }
                if (relStr.length() > 1 && relStr.endsWith("/")) {
                    relStr = relStr.substring(0, relStr.length() - 1);
                }
                return relStr;
            }
        } catch (Exception ignore) {
            // Best-effort only; on non-native platforms this may fail for drive-based paths.
        }
        return null;
    }

    /**
     * Convert a forward-slash path string to a system Path best-effort,
     * accounting for Windows vs POSIX differences.
     */
    @Nullable
    private static Path toSystemPath(String forwardSlashPath) {
        try {
            if (Environment.isWindows()) {
                String winForm = forwardSlashPath.replace('/', '\\');
                return Path.of(winForm);
            } else {
                // POSIX: forward slashes are native
                return Path.of(forwardSlashPath);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
