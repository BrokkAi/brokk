package ai.brokk.util;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.ChangeDelta;
import com.github.difflib.patch.DeleteDelta;
import com.github.difflib.patch.InsertDelta;
import com.github.difflib.patch.Patch;
import com.github.difflib.patch.PatchFailedException;
import com.github.difflib.unifieddiff.UnifiedDiff;
import com.github.difflib.unifieddiff.UnifiedDiffParserException;
import com.github.difflib.unifieddiff.UnifiedDiffReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

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
            String oldContent, String newContent, @Nullable String oldName, @Nullable String newName) {
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
            String oldContent,
            String newContent,
            @Nullable String oldName,
            @Nullable String newName,
            int contextLines) {
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

    /**
     * Parses a raw unified diff string into a {@link UnifiedDiff} structure.
     * This handles common issues like empty file creations and malformed headers.
     *
     * @param diffTxt the raw unified diff text
     * @return an Optional containing the parsed UnifiedDiff, or empty if parsing fails or input is invalid
     */
    public static Optional<UnifiedDiff> parseUnifiedDiff(String diffTxt) {
        if (diffTxt.trim().isEmpty()) {
            return Optional.empty();
        }

        // Check for basic diff structure - should contain "diff --git" or at least "@@ "
        String trimmed = diffTxt.trim();
        if (!trimmed.contains("diff --git") && !trimmed.contains("@@ ")) {
            logger.debug(
                    "Diff text lacks expected diff markers (no 'diff --git' or '@@'), skipping parse. Length: {} chars",
                    diffTxt.length());
            return Optional.empty();
        }

        // Pre-process diff to remove empty file sections that would cause parser failures
        String processedDiff = filterEmptyFileCreations(diffTxt);

        if (processedDiff.trim().isEmpty()) {
            logger.debug("Diff contains only empty file creations, skipping parse");
            return Optional.empty();
        }

        try {
            var input = new ByteArrayInputStream(processedDiff.getBytes(UTF_8));
            return Optional.of(UnifiedDiffReader.parseUnifiedDiff(input));
        } catch (IOException | UnifiedDiffParserException e) {
            logger.warn("Failed to parse unified diff\n{}", diffTxt, e);
            return Optional.empty();
        }
    }

    /**
     * Filters out empty file creations that would cause UnifiedDiffReader to fail. Empty files have index lines like
     * "0000000..e69de29" and include --- /+++ headers but no @@ hunk headers, which confuses the parser.
     */
    private static String filterEmptyFileCreations(String diffTxt) {
        String[] lines = diffTxt.split("\n", -1);
        var result = new ArrayList<String>();
        int i = 0;

        while (i < lines.length) {
            String line = lines[i];

            // Look for file headers
            if (line.startsWith("diff --git ")) {
                int fileStart = i;
                int j = i + 1; // Start looking from the line after diff --git

                // Check if this is an empty file creation
                boolean isEmptyFile = false;
                boolean hasFromToPaths = false;

                // Look ahead to analyze this file section
                while (j < lines.length && !lines[j].startsWith("diff --git ")) {
                    String currentLine = lines[j];

                    // Check for empty blob hash (e69de29 is SHA1 of empty content)
                    if (currentLine.contains("index ") && currentLine.contains("e69de29")) {
                        isEmptyFile = true;
                    }

                    // Check for from/to path headers
                    if (currentLine.startsWith("--- ") && (j + 1 < lines.length) && lines[j + 1].startsWith("+++ ")) {
                        hasFromToPaths = true;
                    }

                    // If we find a hunk header, this is not problematic
                    if (currentLine.startsWith("@@ ")) {
                        isEmptyFile = false;
                        break;
                    }

                    j++;
                }

                // If this is an empty file creation with from/to headers but no hunks, skip it
                if (isEmptyFile && hasFromToPaths) {
                    logger.debug("Filtering out empty file creation: {}", line);
                    i = j; // Skip to next file or end
                    continue;
                }

                // Otherwise, add all lines for this file (including the diff --git line)
                for (int k = fileStart; k < j; k++) {
                    result.add(lines[k]);
                }
                i = j; // Move to next file or end
            } else {
                result.add(line);
                i++;
            }
        }

        return String.join("\n", result);
    }

    /**
     * Convert a text string into a list of lines suitable for java-diff-utils, preserving trailing empty line when the
     * content ends with a newline. This uses split("\\R", -1) to retain final empty element when there is a final
     * newline, which is important for exact diff semantics around end-of-file newline presence.
     */
    private static List<String> toLines(String content) {
        // Split on any line break, preserving trailing empty strings
        // which indicate a final newline.
        return Arrays.asList(content.split("\\R", -1));
    }
}
