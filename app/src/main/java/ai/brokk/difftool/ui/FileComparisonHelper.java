package ai.brokk.difftool.ui;

import ai.brokk.ContextManager;
import ai.brokk.difftool.node.FileNode;
import ai.brokk.difftool.node.JMDiffNode;
import ai.brokk.difftool.node.StringNode;
import ai.brokk.difftool.performance.PerformanceConstants;
import ai.brokk.git.GitRepo;
import ai.brokk.git.IGitRepo;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import javax.swing.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

/**
 * Helper utilities extracted from FileComparison to support both sync and async diff creation. Centralizes common logic
 * for creating diff nodes and handling resources.
 */
public class FileComparisonHelper {

    /** Creates a JMDiffNode from two buffer sources. This is the core logic extracted from FileComparison for reuse. */
    public static JMDiffNode createDiffNode(
            BufferSource left, BufferSource right, ContextManager contextManager, boolean isMultipleCommitsContext) {

        String leftDocSyntaxHint = left.title();
        String leftFullPath = left.title(); // Store full path for FileNode
        if (left instanceof BufferSource.StringSource stringSourceLeft && stringSourceLeft.filename() != null) {
            leftDocSyntaxHint = stringSourceLeft.filename();
            leftFullPath = stringSourceLeft.filename();
        } else if (left instanceof BufferSource.FileSource fileSourceLeft) {
            leftDocSyntaxHint = fileSourceLeft.file().getName();
            leftFullPath = fileSourceLeft.file().getAbsolutePath(); // Use full path
        }

        String rightDocSyntaxHint = right.title();
        String rightFullPath = right.title(); // Store full path for FileNode
        if (right instanceof BufferSource.StringSource stringSourceRight && stringSourceRight.filename() != null) {
            rightDocSyntaxHint = stringSourceRight.filename();
            rightFullPath = stringSourceRight.filename();
        } else if (right instanceof BufferSource.FileSource fileSourceRight) {
            rightDocSyntaxHint = fileSourceRight.file().getName();
            rightFullPath = fileSourceRight.file().getAbsolutePath(); // Use full path
        }

        String leftFileDisplay = leftDocSyntaxHint;
        String nodeTitle;
        if (isMultipleCommitsContext) {
            nodeTitle = leftFileDisplay;
        } else {
            nodeTitle = "%s (%s)".formatted(leftFileDisplay, getDisplayTitleForSource(left, contextManager));
        }
        var node = new JMDiffNode(nodeTitle, true);

        // Handle real files (FileSource) vs virtual files (StringSource)
        // FileSource: Real files on disk, use FileNode with absolute paths for proper ProjectFile creation
        // StringSource: Virtual content from commits/branches, use StringNode with display names
        if (left instanceof BufferSource.FileSource fileSourceLeft) {
            node.setBufferNodeLeft(new FileNode(leftFullPath, fileSourceLeft.file()));
        } else if (left instanceof BufferSource.StringSource stringSourceLeft) {
            node.setBufferNodeLeft(new StringNode(leftDocSyntaxHint, stringSourceLeft.content()));
        }

        if (right instanceof BufferSource.FileSource fileSourceRight) {
            node.setBufferNodeRight(new FileNode(rightFullPath, fileSourceRight.file()));
        } else if (right instanceof BufferSource.StringSource stringSourceRight) {
            node.setBufferNodeRight(new StringNode(rightDocSyntaxHint, stringSourceRight.content()));
        }

        return node;
    }

    /**
     * Gets display title for a buffer source, handling commit message resolution. Extracted from FileComparison for
     * reuse.
     */
    private static String getDisplayTitleForSource(BufferSource source, ContextManager contextManager) {
        String originalTitle = source.title();

        if (source instanceof BufferSource.FileSource) {
            return "Working Tree"; // Represent local files as "Working Tree"
        }

        // For StringSource, originalTitle is typically a commit ID or "HEAD"
        if (originalTitle.isBlank() || originalTitle.equals("HEAD") || originalTitle.startsWith("[No Parent]")) {
            return originalTitle; // Handle special markers or blank as is
        }

        if (source instanceof BufferSource.StringSource stringSource) {
            // Avoid resolving commit metadata for in-memory buffers (no revisionSha) or non-commit-like titles
            if (stringSource.revisionSha() == null || !isCommitLike(originalTitle)) {
                return originalTitle;
            }

            IGitRepo repo = contextManager.getProject().getRepo();
            if (repo instanceof GitRepo gitRepo) { // Ensure it's our GitRepo implementation
                try {
                    String commitIdToLookup = stringSource.revisionSha();
                    var commitInfoOpt = gitRepo.getLocalCommitInfo(commitIdToLookup);
                    if (commitInfoOpt.isPresent()) {
                        return commitInfoOpt.get().message(); // This is already the short/first line
                    }
                } catch (GitAPIException | RuntimeException e) {
                    // Fall back to originalTitle on any parsing or repo error
                    return originalTitle;
                }
            }
        }

        return originalTitle; // Fallback to original commit ID/label if message not found or repo error
    }

    // Heuristic to identify commit-like identifiers to avoid resolving arbitrary human labels
    /**
     * Returns true if the given ref looks like a commit-ish identifier we should try to resolve:
     * - HEAD, HEAD^, HEAD^N
     * - hex SHA-1 (7-40 chars), optionally with ^N suffix
     */
    private static boolean isCommitLike(String ref) {
        var t = ref.trim();
        if (t.isEmpty()) return false;
        if ("HEAD".equals(t)) return true;
        if (t.matches("HEAD(?:\\^\\d*)?")) return true;
        // SHA-1 short or full (7-40 hex), optional ^N
        return t.matches("(?i)[0-9a-f]{7,40}(?:\\^\\d*)?");
    }

    /**
     * Gets the compare icon for tabs. Uses the standard resource loading pattern consistent with other UI components.
     */
    @Nullable
    public static ImageIcon getCompareIcon() {
        try {
            return new ImageIcon(Objects.requireNonNull(FileComparisonHelper.class.getResource("/images/compare.png")));
        } catch (NullPointerException e) {
            return null;
        }
    }

    /**
     * Estimates the size of a BufferSource in bytes. Used for preload size checking to avoid loading files that are too
     * large.
     */
    public static long estimateSourceSize(BufferSource source) {
        if (source instanceof BufferSource.FileSource fileSource) {
            var file = fileSource.file();
            if (file.exists() && file.isFile()) {
                return file.length();
            }
            return 0L;
        } else if (source instanceof BufferSource.StringSource stringSource) {
            return stringSource.content().getBytes(StandardCharsets.UTF_8).length;
        }
        // Unknown source type, return 0
        return 0L;
    }

    /**
     * Validates file sizes before loading to prevent UI issues with large files. Returns null if files are valid to
     * load, otherwise returns an error message.
     */
    @Nullable
    public static String validateFileSizes(BufferSource leftSource, BufferSource rightSource) {
        long leftSize = estimateSourceSize(leftSource);
        long rightSize = estimateSourceSize(rightSource);
        long maxSize = Math.max(leftSize, rightSize);

        if (maxSize > PerformanceConstants.MAX_FILE_SIZE_BYTES) {
            return String.format(
                    "File too large to display: %,d bytes (maximum: %,d bytes)",
                    maxSize, PerformanceConstants.MAX_FILE_SIZE_BYTES);
        }

        if (maxSize > PerformanceConstants.HUGE_FILE_THRESHOLD_BYTES) {
            return String.format(
                    "File too large for responsive UI: %,d bytes (recommended maximum: %,d bytes)",
                    maxSize, PerformanceConstants.HUGE_FILE_THRESHOLD_BYTES);
        }

        return null; // Valid to load
    }

    /**
     * Checks if a file comparison should be loaded for preloading based on size constraints. Returns true if the file
     * is suitable for preloading, false otherwise.
     */
    public static boolean isValidForPreload(BufferSource leftSource, BufferSource rightSource) {
        long leftSize = estimateSourceSize(leftSource);
        long rightSize = estimateSourceSize(rightSource);
        long maxSize = Math.max(leftSize, rightSize);

        return maxSize <= PerformanceConstants.MAX_FILE_SIZE_BYTES;
    }

    /**
     * Creates a file loading result that encapsulates either a successful diff node or an error message for files that
     * are too large.
     */
    public static FileLoadingResult createFileLoadingResult(
            BufferSource leftSource,
            BufferSource rightSource,
            ContextManager contextManager,
            boolean isMultipleCommitsContext) {
        var sizeValidationError = validateFileSizes(leftSource, rightSource);
        if (sizeValidationError != null) {
            return new FileLoadingResult(null, sizeValidationError);
        }

        try {
            var diffNode = createDiffNode(leftSource, rightSource, contextManager, isMultipleCommitsContext);
            return new FileLoadingResult(diffNode, null);
        } catch (Exception e) {
            return new FileLoadingResult(null, "Failed to load file: " + e.getMessage());
        }
    }

    /** Result of file loading operation - either contains a diff node or an error message. */
    public static class FileLoadingResult {
        @Nullable
        private final JMDiffNode diffNode;

        @Nullable
        private final String errorMessage;

        public FileLoadingResult(@Nullable JMDiffNode diffNode, @Nullable String errorMessage) {
            this.diffNode = diffNode;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() {
            return diffNode != null && errorMessage == null;
        }

        @Nullable
        public JMDiffNode getDiffNode() {
            return diffNode;
        }

        @Nullable
        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
