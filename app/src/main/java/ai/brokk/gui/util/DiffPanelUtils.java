package ai.brokk.gui.util;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitWorkflow;
import ai.brokk.git.IGitRepo;
import ai.brokk.util.ContentDiffUtils;

import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
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
        } catch (GitAPIException e) {
            logger.debug("Failed to get file content for {} at {}", file, commitId, e);
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
}
