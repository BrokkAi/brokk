package io.github.jbellis.brokk.gui.mop;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.gui.mop.FilePathResult.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
            // Process each file path
            for (String filePath : filePaths) {
                long startTime = System.currentTimeMillis();
                var result = checkFilePathExists(project, filePath);
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

    /** Check if a single file path exists in the project */
    private static FilePathLookupResult checkFilePathExists(IProject project, String filePath) {
        try {
            // 1. Parse line numbers and clean the path
            var parsed = parseFilePath(filePath);
            var cleanPath = parsed.path();

            // 2. Find all matching files (no extension filtering - if it exists in project, it's valid)
            var matches = findMatchingFiles(project, cleanPath, parsed.lineNumber(), parsed.lineRange());

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
            IProject project, String inputPath, @Nullable Integer lineNumber, @Nullable LineRange lineRange) {
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
                matches.addAll(findRelativePathMatches(project, inputPath, lineNumber, lineRange));
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
            IProject project, String inputPath, @Nullable Integer lineNumber, @Nullable LineRange lineRange) {
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
                // Bare filename - search entire project
                matches.addAll(findFilesByName(project, cleanPath, lineNumber, lineRange));
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

    /** Find all files with matching filename in the project */
    private static List<ProjectFileMatch> findFilesByName(
            IProject project, String filename, @Nullable Integer lineNumber, @Nullable LineRange lineRange) {
        var matches = new ArrayList<ProjectFileMatch>();
        var projectRoot = project.getRoot();

        try {
            // Walk the project directory tree to find matching files
            try (Stream<Path> paths = Files.walk(projectRoot)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equals(filename))
                        .filter(path -> isWithinProject(path, projectRoot))
                        .forEach(path -> {
                            try {
                                var relativePath = projectRoot.relativize(path);
                                matches.add(createProjectFileMatch(
                                        relativePath.toString(), path.toString(), false, lineNumber, lineRange));
                            } catch (Exception e) {
                                logger.debug("Error processing file '{}': {}", path, e.getMessage());
                            }
                        });
            }
        } catch (IOException e) {
            logger.warn("Error walking project directory for filename '{}': {}", filename, e.getMessage());
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
