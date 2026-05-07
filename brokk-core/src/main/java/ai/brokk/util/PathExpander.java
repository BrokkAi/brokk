package ai.brokk.util;

import ai.brokk.analyzer.BrokkFile;
import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.ICoreProject;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.PatternSyntaxException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility for expanding file paths and glob patterns into concrete file references.
 */
public final class PathExpander {

    private static final Logger logger = LogManager.getLogger(PathExpander.class);

    private PathExpander() {}

    /** Expand paths that may contain wildcards (*, ?), returning all matches. */
    public static List<BrokkFile> expandPath(ICoreProject project, String pattern) {
        var paths = expandPatternToPaths(project, pattern);
        var root = project.getRoot().toAbsolutePath().normalize();
        return paths.stream()
                .map(p -> {
                    var abs = p.toAbsolutePath().normalize();
                    if (abs.startsWith(root)) {
                        return new ProjectFile(root, root.relativize(abs));
                    } else {
                        return new ExternalFile(abs);
                    }
                })
                .toList();
    }

    public static BrokkFile maybeExternalFile(Path root, String pathStr) {
        Path p = Path.of(pathStr);
        if (!p.isAbsolute()) {
            return new ProjectFile(root, p);
        }
        if (!p.startsWith(root)) {
            return new ExternalFile(p);
        }
        // we have an absolute path that's part of the project
        return new ProjectFile(root, root.relativize(p));
    }

    /**
     * Expand a path or glob pattern into concrete file Paths. - Supports absolute and relative inputs. - Avoids
     * constructing Path from strings containing wildcards (Windows-safe). - Returns only regular files that exist.
     */
    public static List<Path> expandPatternToPaths(ICoreProject project, String pattern) {
        var trimmed = pattern.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }

        boolean hasGlob = trimmed.indexOf('*') >= 0 || trimmed.indexOf('?') >= 0;
        var sepChar = File.separatorChar;
        var root = project.getRoot().toAbsolutePath().normalize();

        if (!hasGlob) {
            // Exact path (no wildcards)
            if (looksAbsolute(trimmed)) {
                try {
                    var p = Path.of(trimmed).toAbsolutePath().normalize();
                    return Files.isRegularFile(p) ? List.of(p) : List.of();
                } catch (Exception e) {
                    return List.of();
                }
            } else {
                var p = root.resolve(trimmed).toAbsolutePath().normalize();
                return Files.isRegularFile(p) ? List.of(p) : List.of();
            }
        }

        // Globbing path
        int star = trimmed.indexOf('*');
        int ques = trimmed.indexOf('?');
        int firstWildcard = (star == -1) ? ques : (ques == -1 ? star : Math.min(star, ques));

        int lastSepBefore = -1;
        for (int i = firstWildcard - 1; i >= 0; i--) {
            char c = trimmed.charAt(i);
            if (c == '/' || c == '\\') {
                lastSepBefore = i;
                break;
            }
        }
        String basePrefix = lastSepBefore >= 0 ? trimmed.substring(0, lastSepBefore + 1) : "";

        Path baseDir;
        try {
            if (looksAbsolute(trimmed)) {
                if (!basePrefix.isEmpty()) {
                    baseDir = Path.of(basePrefix);
                } else if (trimmed.startsWith("\\\\")) {
                    // UNC root without server/share is not walkable; require at least \\server\share\
                    return List.of();
                } else if (trimmed.length() >= 2 && Character.isLetter(trimmed.charAt(0)) && trimmed.charAt(1) == ':') {
                    baseDir = Path.of(trimmed.charAt(0) + ":\\");
                } else {
                    baseDir = Path.of(File.separator);
                }
            } else {
                var baseRel = basePrefix.replace('/', sepChar).replace('\\', sepChar);
                baseDir = root.resolve(baseRel);
            }
        } catch (InvalidPathException e) {
            // LLMs sometimes pass tokens that are illegal as platform path components
            // (e.g. JSON-array syntax on Windows, where `"`, `<`, `>` are reserved).
            logger.debug(
                    "Invalid base-path token in pattern '{}' (basePrefix='{}'); returning no matches",
                    pattern,
                    basePrefix,
                    e);
            return List.of();
        }

        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }

        // Determine how deep to walk:
        // - If pattern uses **, search recursively.
        // - Otherwise, limit to the number of remaining path segments after the base prefix.
        boolean recursive = trimmed.contains("**");
        String remainder = lastSepBefore >= 0 ? trimmed.substring(lastSepBefore + 1) : trimmed;
        int remainingSeparators = 0;
        for (int i = 0; i < remainder.length(); i++) {
            char c = remainder.charAt(i);
            if (c == '/' || c == '\\') remainingSeparators++;
        }
        int maxDepth = recursive ? Integer.MAX_VALUE : (1 + remainingSeparators);

        // Build a matcher relative to baseDir to avoid Windows absolute glob quirks.
        // Globs always use '/' as the path separator (java.nio.file.FileSystem#getPathMatcher);
        // on Windows, "/" in a glob still matches "\" in a Path, so we normalize '\' -> '/' but
        // never the reverse: replacing '/' with '\' on Windows would turn the separator into the
        // glob escape character and break "**/*.rs"-style patterns.
        String relGlob = remainder.replace('\\', '/');
        PathMatcher matcher;
        try {
            // getPathMatcher also declares IllegalArgumentException for an unknown syntax identifier,
            // but with the literal "glob:" prefix used here that branch is unreachable, so the catch
            // stays narrow.
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + relGlob);
        } catch (PatternSyntaxException e) {
            logger.debug("Invalid glob pattern '{}' (relGlob='{}'); returning no matches", pattern, relGlob, e);
            return List.of();
        }

        var matches = new ArrayList<Path>();
        try {
            Files.walkFileTree(baseDir, EnumSet.noneOf(FileVisitOption.class), maxDepth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && matcher.matches(baseDir.relativize(file))) {
                        matches.add(file.toAbsolutePath().normalize());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    logger.debug(
                            "Skipping path during wildcard expansion due to access issue: {} ({})",
                            file,
                            exc.getClass().getSimpleName());
                    return FileVisitResult.CONTINUE;
                }
            });
            return matches;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean looksAbsolute(String s) {
        if (s.startsWith("/")) {
            return true;
        }
        if (s.startsWith("\\\\")) { // UNC path
            return true;
        }
        return s.length() >= 3
                && Character.isLetter(s.charAt(0))
                && s.charAt(1) == ':'
                && (s.charAt(2) == '\\' || s.charAt(2) == '/');
    }
}
