package ai.brokk.util;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.ChangeDelta;
import com.github.difflib.patch.DeleteDelta;
import com.github.difflib.patch.InsertDelta;
import com.github.difflib.patch.Patch;
import com.github.difflib.patch.PatchFailedException;
import java.util.Arrays;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ContentDiffUtils {
    private static final Logger logger = LogManager.getLogger(ContentDiffUtils.class);

    public static String diff(String oldContent, String newContent) {
        var result = computeDiffResult(oldContent, newContent, "old", "new");
        return result.diff();
    }

    public static String applyDiff(String diff, String oldContent) {
        if (diff.isBlank()) {
            return oldContent;
        }
        var diffLines = diff.lines().toList();
        var patch = UnifiedDiffUtils.parseUnifiedDiff(diffLines);
        try {
            var oldLines = toLines(oldContent);
            var newLines = patch.applyTo(oldLines);
            return String.join("\n", newLines);
        } catch (PatchFailedException e) {
            throw new RuntimeException("Failed to apply patch", e);
        }
    }

    public record DiffComputationResult(String diff, int added, int deleted) {}

    /**
     * Compute a unified diff and change counts (added/deleted) between two strings using java-diff-utils.
     *
     * @param oldContent baseline content
     * @param newContent revised content
     * @param oldName filename label for "from"
     * @param newName filename label for "to"
     * @return DiffComputationResult containing unified diff text and counts
     */
    public static DiffComputationResult computeDiffResult(
            String oldContent, String newContent, String oldName, String newName) {
        return computeDiffResult(oldContent, newContent, oldName, newName, 0);
    }

    /**
     * Compute a unified diff and change counts (added/deleted) between two strings using java-diff-utils.
     *
     * @param oldContent baseline content
     * @param newContent revised content
     * @param oldName filename label for "from"
     * @param newName filename label for "to"
     * @param contextLines number of context lines to include in the unified diff hunks
     * @return DiffComputationResult containing unified diff text and counts
     */
    public static DiffComputationResult computeDiffResult(
            String oldContent, String newContent, String oldName, String newName, int contextLines) {
        var oldLines = toLines(oldContent);
        var newLines = toLines(newContent);

        Patch<String> patch = DiffUtils.diff(oldLines, newLines);
        if (patch.getDeltas().isEmpty()) {
            if (logger.isTraceEnabled()) {
                logger.trace(
                        "computeDiffResult: {} -> {} | no changes (oldLines={}, newLines={})",
                        oldName,
                        newName,
                        oldLines.size(),
                        newLines.size());
            }
            return new DiffComputationResult("", 0, 0);
        }

        int added = 0;
        int deleted = 0;
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            if (delta instanceof InsertDelta<String> id) {
                added += id.getTarget().size();
            } else if (delta instanceof DeleteDelta<String> dd) {
                deleted += dd.getSource().size();
            } else if (delta instanceof ChangeDelta<String> cd) {
                added += cd.getTarget().size();
                deleted += cd.getSource().size();
            }
        }

        if (logger.isTraceEnabled()) {
            logger.trace(
                    "computeDiffResult: {} -> {} | deltas={} added={} deleted={} (oldLines={}, newLines={}, context={})",
                    oldName,
                    newName,
                    patch.getDeltas().size(),
                    added,
                    deleted,
                    oldLines.size(),
                    newLines.size(),
                    contextLines);
        }

        var diffLines = UnifiedDiffUtils.generateUnifiedDiff(oldName, newName, oldLines, patch, contextLines);
        var diffText = String.join("\n", diffLines);
        return new DiffComputationResult(diffText, added, deleted);
    }

    private static List<String> toLines(String content) {
        // Split on any line break, preserving trailing empty strings
        // which indicate a final newline.
        return Arrays.asList(content.split("\\R", -1));
    }
}
