package ai.brokk.gui.util;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitWorkflow;
import ai.brokk.git.IGitRepo;
import ai.brokk.util.ContentDiffUtils;
import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Shared utilities for building diff panels and computing diff statistics.
 */
public final class DiffPanelUtils {
    private static final Logger logger = LogManager.getLogger(DiffPanelUtils.class);

    private DiffPanelUtils() {}

    /** Per-file change data for diff display. */
    public record PerFileChange(String displayFile, String leftContent, String rightContent) {}

    /** Cumulative changes summary across multiple files. */
    public record CumulativeChanges(
            int filesChanged,
            int totalAdded,
            int totalDeleted,
            List<PerFileChange> perFileChanges,
            @Nullable GitWorkflow.PushPullState pushPullState) {

        /** Convenience constructor without pushPullState. */
        public CumulativeChanges(
                int filesChanged, int totalAdded, int totalDeleted, List<PerFileChange> perFileChanges) {
            this(filesChanged, totalAdded, totalDeleted, perFileChanges, null);
        }
    }

    /**
     * Safely retrieves file content at a specific commit, returning empty string on error.
     */
    public static String safeGetFileContent(IGitRepo repo, String commitId, ProjectFile file) {
        try {
            String content = repo.getFileContent(commitId, file);
            return content.isEmpty() ? "" : content;
        } catch (Exception e) {
            logger.debug("Failed to get file content for {} at {}", file, commitId, e);
            return "";
        }
    }

    /**
     * Safely reads file content from the working tree, returning empty string on error.
     */
    public static String safeReadWorkingTree(ProjectFile file) {
        try {
            if (Files.exists(file.absPath())) {
                return Files.readString(file.absPath(), StandardCharsets.UTF_8);
            } else {
                return "";
            }
        } catch (Exception e) {
            logger.debug("Failed to read working tree file {}", file, e);
            return "";
        }
    }

    /**
     * Computes the number of lines added and deleted between two content strings.
     *
     * @return array of [added, deleted] counts
     */
    public static int[] computeNetLineCounts(String left, String right) {
        var result = ContentDiffUtils.computeDiffResult(left, right, "old", "new");
        return new int[] {result.added(), result.deleted()};
    }

    /**
     * Converts a Color to a hex string suitable for HTML/CSS.
     */
    public static String toHex(@Nullable Color c) {
        if (c == null) return "#000000";
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
