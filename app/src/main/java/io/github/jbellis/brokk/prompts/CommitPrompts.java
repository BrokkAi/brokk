package io.github.jbellis.brokk.prompts;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import com.github.difflib.patch.DeltaType;
import com.github.difflib.unifieddiff.UnifiedDiff;
import com.github.difflib.unifieddiff.UnifiedDiffFile;
import com.github.difflib.unifieddiff.UnifiedDiffReader;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.difftool.performance.PerformanceConstants;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CommitPrompts {
    private static final Logger logger = LogManager.getLogger(CommitPrompts.class);

    public static final CommitPrompts instance = new CommitPrompts() {};

    private static final int FILE_LIMIT = 5;
    private static final int LINES_PER_FILE = 100;

    private CommitPrompts() {}

    public List<ChatMessage> collectMessages(IProject project, String diffTxt) {
        if (diffTxt.isEmpty()) {
            return List.of();
        }

        var trimmedDiff = preprocessUnifiedDiff(diffTxt, FILE_LIMIT);
        if (trimmedDiff.isBlank()) {
            return List.of();
        }

        var formatInstructions = project.getCommitMessageFormat();

        var context = """
        <diff>
        %s
        </diff>
        """.formatted(trimmedDiff);

        var instructions =
                """
        <goal>
        Here is my diff, please give me a concise commit message based on the format instructions provided in the system prompt.
        </goal>
        """;
        return List.of(
                new SystemMessage(systemIntro(formatInstructions)), new UserMessage(context + "\n\n" + instructions));
    }

    private String systemIntro(String formatInstructions) {
        return """
               You are an expert software engineer that generates concise,
               one-line Git commit messages based on the provided diffs.
               Review the provided context and diffs which are about to be committed to a git repo.
               Review the diffs carefully.
               Generate a one-line commit message for those changes, following the format instructions below.
               %s

               Ensure the commit message:
               - Follows the specified format.
               - Is in the imperative mood (e.g., "Add feature" not "Added feature" or "Adding feature").
               - Does not exceed 72 characters.

               Additionally, if a single file is changed be sure to include the short filename (not the path, not the extension).

               Reply only with the one-line commit message, without any additional text, explanations,
               or line breaks.
               """
                .formatted(formatInstructions);
    }

    public String preprocessUnifiedDiff(String diffTxt, int fileCount) {
        // Pre-validate the diff text to avoid unnecessary parsing attempts
        if (diffTxt.trim().isEmpty()) {
            return "";
        }

        // Check for basic diff structure - should contain "diff --git" or at least "@@ "
        String trimmed = diffTxt.trim();
        if (!trimmed.contains("diff --git") && !trimmed.contains("@@ ")) {
            logger.debug(
                    "Diff text lacks expected diff markers (no 'diff --git' or '@@'), skipping parse. Length: {} chars",
                    diffTxt.length());
            if (logger.isTraceEnabled()) {
                logger.trace(
                        "Rejected diff text (first 200 chars): {}",
                        diffTxt.length() > 200 ? diffTxt.substring(0, 200) + "..." : diffTxt);
            }
            return "";
        }

        // Pre-process diff to remove empty file sections that would cause parser failures
        String processedDiff = filterEmptyFileCreations(diffTxt);

        if (processedDiff.trim().isEmpty()) {
            logger.debug("Diff contains only empty file creations, skipping commit message generation");
            return "";
        }

        List<UnifiedDiffFile> files;
        try {
            var input = new ByteArrayInputStream(processedDiff.getBytes(UTF_8));
            UnifiedDiff unified = UnifiedDiffReader.parseUnifiedDiff(input);
            files = List.copyOf(unified.getFiles());
        } catch (IOException | RuntimeException e) {
            logger.debug(
                    "Failed to parse unified diff: {} (length: {} chars, {} lines)",
                    e.getMessage(),
                    diffTxt.length(),
                    diffTxt.split("\n").length);

            if (logger.isTraceEnabled()) {
                logger.trace("Full diff text that failed to parse:\n{}", diffTxt);
            }
            return "";
        }

        // Filter invalid deltas (containing overlong line), compute metrics
        record FileMetrics(UnifiedDiffFile file, List<AbstractDelta<String>> deltas, int hunkCount, int totalLines) {}

        var candidates = new ArrayList<FileMetrics>();
        for (var f : files) {
            var patch = f.getPatch();
            if (patch == null) continue;
            var valid =
                    patch.getDeltas().stream().filter(d -> !hasOverlongLine(d)).toList();
            if (valid.isEmpty()) {
                continue;
            }
            int count = valid.size();
            int total = valid.stream().mapToInt(CommitPrompts::deltaSize).sum();
            candidates.add(new FileMetrics(f, valid, count, total));
        }

        if (candidates.isEmpty()) return "";

        // Sort by number of hunks (desc) then total lines (desc)
        candidates.sort(Comparator.comparingInt(FileMetrics::hunkCount)
                .reversed()
                .thenComparing(Comparator.comparingInt(FileMetrics::totalLines).reversed()));

        // For each file, add hunks in decreasing size until reaching LINES_PER_FILE_LIMIT.
        var output = new ArrayList<String>();
        for (var fm : candidates.subList(0, Math.min(fileCount, candidates.size()))) {
            var f = fm.file();

            // Build a/b paths similar to git
            var from = f.getFromFile();
            var to = f.getToFile();
            var aPath = (from == null || "/dev/null".equals(from))
                    ? "/dev/null"
                    : (from.startsWith("a/") ? from : "a/" + from);
            var bPath = (to == null || "/dev/null".equals(to)) ? "/dev/null" : (to.startsWith("b/") ? to : "b/" + to);

            // file header
            output.add("diff --git " + aPath + " " + bPath);
            output.add("--- " + aPath);
            output.add("+++ " + bPath);

            var deltas = new ArrayList<>(fm.deltas());
            deltas.sort(Comparator.comparingInt(CommitPrompts::deltaSize).reversed());

            int added = 0;
            boolean includedAtLeastOne = false;
            for (var d : deltas) {
                int size = deltaSize(d);
                var lines = deltaAsUnifiedLines(d);
                if (!includedAtLeastOne && size > LINES_PER_FILE) {
                    // Include the largest hunk even if it exceeds the limit
                    output.addAll(lines);
                    includedAtLeastOne = true;
                    break;
                }
                if (added + size <= LINES_PER_FILE) {
                    output.addAll(lines);
                    added += size;
                    includedAtLeastOne = true;
                } else {
                    // Stop when adding the next hunk would exceed the limit
                    break;
                }
            }
        }

        return String.join("\n", output);
    }

    private static int deltaSize(AbstractDelta<String> d) {
        var src = d.getSource();
        var tgt = d.getTarget();
        int size = 1; // header line
        switch (d.getType()) {
            case DELETE -> size += src.size();
            case INSERT -> size += tgt.size();
            case CHANGE -> size += src.size() + tgt.size();
            default -> size += src.size() + tgt.size();
        }
        return size;
    }

    private static boolean hasOverlongLine(AbstractDelta<String> d) {
        // Check only data lines; header is always short
        var src = d.getSource();
        var tgt = d.getTarget();
        if (d.getType() == DeltaType.DELETE || d.getType() == DeltaType.CHANGE) {
            for (var s : src.getLines()) {
                if (("-" + s).getBytes(UTF_8).length > PerformanceConstants.MAX_DIFF_LINE_LENGTH_BYTES) return true;
            }
        }
        if (d.getType() == DeltaType.INSERT || d.getType() == DeltaType.CHANGE) {
            for (var t : tgt.getLines()) {
                if (("+" + t).getBytes(UTF_8).length > PerformanceConstants.MAX_DIFF_LINE_LENGTH_BYTES) return true;
            }
        }
        return false;
    }

    private static List<String> deltaAsUnifiedLines(AbstractDelta<String> d) {
        Chunk<String> src = d.getSource();
        Chunk<String> tgt = d.getTarget();
        DeltaType type = d.getType();

        int oldLn = src.getPosition() + 1; // 1-based
        int oldSz = src.size();
        int newLn = tgt.getPosition() + 1; // 1-based
        int newSz = tgt.size();

        var lines = new ArrayList<String>();
        lines.add(String.format("@@ -%d,%d +%d,%d @@", oldLn, oldSz, newLn, newSz));

        if (type == DeltaType.DELETE || type == DeltaType.CHANGE) {
            for (var s : src.getLines()) lines.add("-" + s);
        }
        if (type == DeltaType.INSERT || type == DeltaType.CHANGE) {
            for (var t : tgt.getLines()) lines.add("+" + t);
        }
        return lines;
    }

    /**
     * Filters out empty file creations that would cause UnifiedDiffReader to fail. Empty files have index lines like
     * "0000000..e69de29" and include --- /+++ headers but no @@ hunk headers, which confuses the parser.
     */
    private String filterEmptyFileCreations(String diffTxt) {
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
}
