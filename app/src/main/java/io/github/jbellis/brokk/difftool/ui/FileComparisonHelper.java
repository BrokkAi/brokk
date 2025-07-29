package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.difftool.node.FileNode;
import io.github.jbellis.brokk.difftool.node.JMDiffNode;
import io.github.jbellis.brokk.difftool.node.StringNode;
import io.github.jbellis.brokk.difftool.performance.PerformanceConstants;
import io.github.jbellis.brokk.git.CommitInfo;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.IGitRepo;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * Helper utilities extracted from FileComparison to support both sync and async diff creation.
 * Centralizes common logic for creating diff nodes and handling resources.
 */
public class FileComparisonHelper {

    /**
     * Creates a JMDiffNode from two buffer sources.
     * This is the core logic extracted from FileComparison for reuse.
     */
    public static JMDiffNode createDiffNode(BufferSource left, BufferSource right,
                                          ContextManager contextManager, boolean isMultipleCommitsContext) {

        String leftDocSyntaxHint = left.title();
        if (left instanceof BufferSource.StringSource stringSourceLeft && stringSourceLeft.filename() != null) {
            leftDocSyntaxHint = stringSourceLeft.filename();
        } else if (left instanceof BufferSource.FileSource fileSourceLeft) {
            leftDocSyntaxHint = fileSourceLeft.file().getName();
        }

        String rightDocSyntaxHint = right.title();
        if (right instanceof BufferSource.StringSource stringSourceRight && stringSourceRight.filename() != null) {
            rightDocSyntaxHint = stringSourceRight.filename();
        } else if (right instanceof BufferSource.FileSource fileSourceRight) {
            rightDocSyntaxHint = fileSourceRight.file().getName();
        }

        String leftFileDisplay = leftDocSyntaxHint;
        String nodeTitle;
        if (isMultipleCommitsContext) {
            nodeTitle = leftFileDisplay;
        } else {
            nodeTitle = "%s (%s)".formatted(leftFileDisplay, getDisplayTitleForSource(left, contextManager));
        }
        var node = new JMDiffNode(nodeTitle, true);

        if (left instanceof BufferSource.FileSource fileSourceLeft) {
            node.setBufferNodeLeft(new FileNode(leftDocSyntaxHint, fileSourceLeft.file()));
        } else if (left instanceof BufferSource.StringSource stringSourceLeft) {
            node.setBufferNodeLeft(new StringNode(leftDocSyntaxHint, stringSourceLeft.content()));
        }

        if (right instanceof BufferSource.FileSource fileSourceRight) {
            node.setBufferNodeRight(new FileNode(rightDocSyntaxHint, fileSourceRight.file()));
        } else if (right instanceof BufferSource.StringSource stringSourceRight) {
            node.setBufferNodeRight(new StringNode(rightDocSyntaxHint, stringSourceRight.content()));
        }

        return node;
    }

    /**
     * Gets display title for a buffer source, handling commit message resolution.
     * Extracted from FileComparison for reuse.
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

        // Attempt to treat as commitId and fetch message
        IGitRepo repo = contextManager.getProject().getRepo();
        if (repo instanceof GitRepo gitRepo) { // Ensure it's our GitRepo implementation
            try {
                String commitIdToLookup = originalTitle.endsWith("^") ? originalTitle.substring(0, originalTitle.length() - 1) : originalTitle;
                Optional<CommitInfo> commitInfoOpt = gitRepo.getLocalCommitInfo(commitIdToLookup);
                if (commitInfoOpt.isPresent()) {
                    return commitInfoOpt.get().message(); // This is already the short/first line
                }
            } catch (GitAPIException e) {
                // Fall through to return originalTitle
            }
        }
        return originalTitle; // Fallback to original commit ID if message not found or repo error
    }

    /**
     * Gets the compare icon for tabs.
     * Uses the standard resource loading pattern consistent with other UI components.
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
     * Estimates the size of a BufferSource in bytes.
     * Used for preload size checking to avoid loading files that are too large.
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
     * Validates file sizes before loading to prevent UI issues with large files.
     * Returns null if files are valid to load, otherwise returns an error message.
     */
    @Nullable
    public static String validateFileSizes(BufferSource leftSource, BufferSource rightSource) {
        long leftSize = estimateSourceSize(leftSource);
        long rightSize = estimateSourceSize(rightSource);
        long maxSize = Math.max(leftSize, rightSize);

        if (maxSize > PerformanceConstants.MAX_FILE_SIZE_BYTES) {
            return String.format("File too large to display: %,d bytes (maximum: %,d bytes)",
                                maxSize, PerformanceConstants.MAX_FILE_SIZE_BYTES);
        }

        if (maxSize > PerformanceConstants.HUGE_FILE_THRESHOLD_BYTES) {
            return String.format("File too large for responsive UI: %,d bytes (recommended maximum: %,d bytes)",
                                maxSize, PerformanceConstants.HUGE_FILE_THRESHOLD_BYTES);
        }

        return null; // Valid to load
    }

    /**
     * Checks if a file comparison should be loaded for preloading based on size constraints.
     * Returns true if the file is suitable for preloading, false otherwise.
     */
    public static boolean isValidForPreload(BufferSource leftSource, BufferSource rightSource) {
        long leftSize = estimateSourceSize(leftSource);
        long rightSize = estimateSourceSize(rightSource);
        long maxSize = Math.max(leftSize, rightSize);

        return maxSize <= PerformanceConstants.MAX_FILE_SIZE_BYTES;
    }

    /**
     * Creates a file loading result that encapsulates either a successful diff node
     * or an error message for files that are too large.
     */
    public static FileLoadingResult createFileLoadingResult(BufferSource leftSource, BufferSource rightSource,
                                                          ContextManager contextManager, boolean isMultipleCommitsContext) {
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

    /**
     * Result of file loading operation - either contains a diff node or an error message.
     */
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
