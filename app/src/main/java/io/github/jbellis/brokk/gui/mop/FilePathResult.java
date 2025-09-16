package io.github.jbellis.brokk.gui.mop;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Result classes for file path lookup operations. These records match the TypeScript interfaces defined in
 * filePathCacheStore.ts
 */
public class FilePathResult {

    /** Main result for a file path lookup operation */
    public record FilePathLookupResult(
            boolean exists, List<ProjectFileMatch> matches, int confidence, long processingTimeMs) {
        public static FilePathLookupResult notFound(String originalPath) {
            return new FilePathLookupResult(false, List.of(), 0, 0);
        }

        public static FilePathLookupResult found(List<ProjectFileMatch> matches, long processingTimeMs) {
            return new FilePathLookupResult(true, matches, 100, processingTimeMs);
        }
    }

    /** Individual file match within a project */
    public record ProjectFileMatch(
            String relativePath,
            String absolutePath,
            boolean isDirectory,
            @Nullable Integer lineNumber,
            @Nullable LineRange lineRange) {}

    /** Line range for file paths like "file.py:15-20" */
    public record LineRange(int start, int end) {}

    /** Parsed file path with extracted line numbers */
    public record ParsedFilePath(
            String path, @Nullable Integer lineNumber, @Nullable LineRange lineRange, String originalText) {}
}
