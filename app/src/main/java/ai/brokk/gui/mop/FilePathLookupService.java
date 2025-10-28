package ai.brokk.gui.mop;

import ai.brokk.IContextManager;
import ai.brokk.IProject;
import ai.brokk.gui.mop.FilePathResult.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for looking up file paths in the project, parallel to SymbolLookupService. Handles file path validation,
 * existence checking, and duplicate file resolution.
 */
public class FilePathLookupService {
    private static final Logger logger = LoggerFactory.getLogger(FilePathLookupService.class);

    // Pattern to match line numbers: :42 or :15-20
    private static final Pattern LINE_NUMBER_PATTERN = Pattern.compile("^(.+?):([0-9]+)(?:-([0-9]+))?$");

    // Lightweight, per-project filename index to avoid expensive Files.walk
    private static final long INDEX_TTL_MS = 30_000L;
    private static final Map<Path, CachedIndex> INDEX_CACHE = new ConcurrentHashMap<>();

    private record IndexedMatch(String relativePath, String absolutePath, boolean isDirectory) {}

    private static final class CachedIndex {
        final Map<String, List<IndexedMatch>> byName;
        final long builtAt;

        CachedIndex(Map<String, List<IndexedMatch>> byName, long builtAt) {
            this.byName = byName;
            this.builtAt = builtAt;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - builtAt > INDEX_TTL_MS;
        }
    }

    /**
     * Main entry point for file path lookup
     *
     * @param filePaths Set of file paths to look up
     * @param contextManager Context manager for project access (nullable for testing)
     * @param resultCallback Callback for individual file path results
     * @param completionCallback Optional callback when all lookups are complete
     */
    public static void lookupFilePaths(
            Set<String> filePaths,
            @Nullable IContextManager contextManager,
            BiConsumer<String, FilePathLookupResult> resultCallback,
            @Nullable Runnable completionCallback) {

        if (contextManager == null) {
            logger.warn("Context manager is null, cannot perform file path lookup");
            if (completionCallback != null) {
                completionCallback.run();
            }
            return;
        }

        var project = contextManager.getProject();

        logger.debug("Looking up {} file paths", filePaths.size());

        try {
            // Build or reuse an index of tracked files once for this batch
            var index = getOrBuildIndex(project);

            // Process each file path
            for (String filePath : filePaths) {
                long startTime = System.currentTimeMillis();
                var result = checkFilePathExists(project, filePath, index);
                long processingTime = System.currentTimeMillis() - startTime;

                // Update processing time in result
                result = new FilePathLookupResult(
                        result.exists(), result.matches(), result.confidence(), processingTime);

                resultCallback.accept(filePath, result);

                logger.debug(
                        "File path '{}' lookup completed in {}ms: exists={}, matches={}",
                        filePath,
                        processingTime,
                        result.exists(),
                        result.matches().size());
            }
        } catch (Exception e) {
            logger.error("Error during file path lookup", e);
        } finally {
            if (completionCallback != null) {
                completionCallback.run();
            }
        }
    }

    /** Build or reuse a cached filename index for the project (TTL-based). */
    private static CachedIndex getOrBuildIndex(IProject project) {
        var key = project.getRoot().toAbsolutePath().normalize();
        var existing = INDEX_CACHE.get(key);
        if (existing != null && !existing.isExpired()) {
            return existing;
        }
        long start = System.currentTimeMillis();
        var byName = buildIndexFromTrackedFiles(project);
        var fresh = new CachedIndex(byName, System.currentTimeMillis());
        INDEX_CACHE.put(key, fresh);
        logger.debug(
                "FilePathLookupService: built filename index for {} with {} entries in {}ms",
                key,
                byName.size(),
                fresh.builtAt - start);
        return fresh;
    }

    /** Build a filename -> list of matches map from the repo's tracked files. */
    private static Map<String, List<IndexedMatch>> buildIndexFromTrackedFiles(IProject project) {
        var root = project.getRoot();
        var tracked = project.getRepo().getTrackedFiles(); // honors ignore rules; avoids scanning .git/.brokk
        Map<String, List<IndexedMatch>> byName = new HashMap<>(Math.max(16, tracked.size()));
        for (var pf : tracked) {
            var abs = pf.absPath();
            var rel = root.relativize(abs).toString();
            var name = abs.getFileName().toString();
            var entry = new IndexedMatch(rel, abs.toString(), Files.isDirectory(abs));
            byName.computeIfAbsent(name, k -> new ArrayList<>()).add(entry);
        }
        return byName;
    }

    /** Check if a single file path exists in the project (using the cached index for filename lookups). */
    private static FilePathLookupResult checkFilePathExists(IProject project, String filePath, CachedIndex index) {
        try {
            // 1. Parse line numbers and clean the path
            var parsed = parseFilePath(filePath);
            var cleanPath = parsed.path();

            // 2. Find all matching files (no extension filtering - if it exists in project, it's valid)
            var matches = findMatchingFiles(project, cleanPath, parsed.lineNumber(), parsed.lineRange(), index);

            if (matches.isEmpty()) {
                logger.debug("No matches found for file path '{}'", filePath);
                return FilePathLookupResult.notFound(filePath);
            }

            logger.debug("Found {} matches for file path '{}'", matches.size(), filePath);
            return FilePathLookupResult.found(matches, 0); // Processing time will be set by caller

        } catch (Exception e) {
            logger.warn("Error checking file path existence for '{}': {}", filePath, e.getMessage());
            return FilePathLookupResult.notFound(filePath);
        }
    }

    /** Parse line numbers from file path like "file.js:42" or "file.py:15-20" */
    private static ParsedFilePath parseFilePath(String input) {
        var matcher = LINE_NUMBER_PATTERN.matcher(input.trim());

        if (matcher.matches()) {
            var path = matcher.group(1);
            var startLine = Integer.parseInt(matcher.group(2));
            var endLine = matcher.group(3);

            if (endLine != null) {
                var endLineNum = Integer.parseInt(endLine);
                return new ParsedFilePath(path, null, new LineRange(startLine, endLineNum), input);
            } else {
                return new ParsedFilePath(path, startLine, null, input);
            }
        }

        return new ParsedFilePath(input.trim(), null, null, input);
    }

    /** Find all files matching the given path in the project */
    private static List<ProjectFileMatch> findMatchingFiles(
            IProject project,
            String inputPath,
            @Nullable Integer lineNumber,
            @Nullable LineRange lineRange,
            CachedIndex index) {
        var matches = new ArrayList<ProjectFileMatch>();
        var projectRoot = project.getRoot();

        try {
            // Handle different path types
            if (isAbsolutePath(inputPath)) {
                // Absolute path - check if it exists and is within project
                var absolutePath = Path.of(inputPath).normalize();
                if (isWithinProject(absolutePath, projectRoot)) {
                    var relativePath = projectRoot.relativize(absolutePath);
                    if (Files.exists(absolutePath)) {
                        matches.add(createProjectFileMatch(
                                relativePath.toString(),
                                absolutePath.toString(),
                                Files.isDirectory(absolutePath),
                                lineNumber,
                                lineRange));
                    }
                }
            } else {
                // Relative path or bare filename - search within project
                matches.addAll(findRelativePathMatches(project, inputPath, lineNumber, lineRange, index));
            }

        } catch (Exception e) {
            logger.warn("Error finding matches for path '{}': {}", inputPath, e.getMessage());
        }

        return matches;
    }

    /** Check if path is absolute */
    private static boolean isAbsolutePath(String path) {
        return Path.of(path).isAbsolute();
    }

    /** Check if absolute path is within the project root */
    private static boolean isWithinProject(Path absolutePath, Path projectRoot) {
        try {
            var normalized = absolutePath.normalize();
            var normalizedRoot = projectRoot.normalize();
            return normalized.startsWith(normalizedRoot);
        } catch (Exception e) {
            return false;
        }
    }

    /** Find matches for relative paths and bare filenames */
    private static List<ProjectFileMatch> findRelativePathMatches(
            IProject project,
            String inputPath,
            @Nullable Integer lineNumber,
            @Nullable LineRange lineRange,
            CachedIndex index) {
        var matches = new ArrayList<ProjectFileMatch>();
        var projectRoot = project.getRoot();
        var cleanPath = removeQuotes(inputPath);

        try {
            if (cleanPath.contains("/") || cleanPath.contains("\\")) {
                // Relative path with directories
                var targetPath = projectRoot.resolve(cleanPath).normalize();
                if (Files.exists(targetPath) && isWithinProject(targetPath, projectRoot)) {
                    var relativePath = projectRoot.relativize(targetPath);
                    matches.add(createProjectFileMatch(
                            relativePath.toString(),
                            targetPath.toString(),
                            Files.isDirectory(targetPath),
                            lineNumber,
                            lineRange));
                }
            } else {
                // Bare filename - use the cached filename index
                matches.addAll(findFilesByName(cleanPath, lineNumber, lineRange, index));
            }
        } catch (Exception e) {
            logger.warn("Error finding relative path matches for '{}': {}", inputPath, e.getMessage());
        }

        return matches;
    }

    /** Remove surrounding quotes from path */
    private static String removeQuotes(String path) {
        var trimmed = path.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** Find all files with matching filename in the project (via cached index, no disk walk). */
    private static List<ProjectFileMatch> findFilesByName(
            String filename, @Nullable Integer lineNumber, @Nullable LineRange lineRange, CachedIndex index) {
        var matches = new ArrayList<ProjectFileMatch>();
        var candidates = index.byName.get(filename);
        if (candidates == null || candidates.isEmpty()) {
            return matches;
        }
        for (IndexedMatch im : candidates) {
            matches.add(createProjectFileMatch(
                    im.relativePath(), im.absolutePath(), im.isDirectory(), lineNumber, lineRange));
        }
        return matches;
    }

    /** Create a ProjectFileMatch with the given parameters */
    private static ProjectFileMatch createProjectFileMatch(
            String relativePath,
            String absolutePath,
            boolean isDirectory,
            @Nullable Integer lineNumber,
            @Nullable LineRange lineRange) {
        return new ProjectFileMatch(relativePath, absolutePath, isDirectory, lineNumber, lineRange);
    }
}
