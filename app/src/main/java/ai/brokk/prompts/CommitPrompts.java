package ai.brokk.prompts;

import static java.nio.charset.StandardCharsets.UTF_8;

import ai.brokk.difftool.performance.PerformanceConstants;
import ai.brokk.project.IProject;
import ai.brokk.util.ContentDiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import com.github.difflib.patch.DeltaType;
import com.github.difflib.unifieddiff.UnifiedDiffFile;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CommitPrompts {
    public static final CommitPrompts instance = new CommitPrompts() {};

    static final int FILE_LIMIT = 5;
    static final int LINES_PER_FILE = 100;

    private CommitPrompts() {}

    /**
     * Collect chat messages for asking the model to produce a commit message.
     *
     * @param project the project for obtaining format instructions
     * @param diffTxt unified diff text
     * @param oneLine if true, request a single-line commit message; if false, request a subject + body
     * @return list of ChatMessage for the LLM, or empty list if diff is blank or unparseable
     */
    public List<ChatMessage> collectMessages(IProject project, String diffTxt, boolean oneLine) {
        if (diffTxt.isEmpty()) {
            return List.of();
        }

        var trimmedDiff = preprocessUnifiedDiff(diffTxt, FILE_LIMIT, LINES_PER_FILE);
        if (trimmedDiff.isBlank()) {
            return List.of();
        }

        var formatInstructions = project.getCommitMessageFormat();

        var context = """
            <diff>
            %s
            </diff>
            """.formatted(trimmedDiff);

        final String instructions;
        if (oneLine) {
            instructions =
                    """
                    <goal>
                    Here is my diff. Provide a single-line commit subject as JSON.

                    Requirements:
                    - Output ONLY a JSON object.
                    - Use exactly this shape: {"subject":"...","body":[],"useBody":false}
                    - The subject must be exactly one line.
                    - Do not exceed 72 characters in the subject.
                    - If a single file is changed, include its short filename (not the path, not the extension).
                    - body must be an empty array.
                    - useBody must be false.
                    </goal>
                    """;
        } else {
            instructions =
                    """
                    <goal>
                    Generate a concise Git commit message based on the provided diff as JSON.

                    Your primary objective is brevity. Most commits should ONLY contain the subject line.
                    Use a multi-line body only for significant logic changes or architectural shifts.

                    Output format:
                    - Output ONLY a JSON object.
                    - Use exactly this shape: {"subject":"...","body":["..."],"useBody":true}
                    - If the change is simple, set "body" to [] and "useBody" to false.
                    - Subject line: Max 72 characters. If one file changed, include its name (no path/extension).
                    - Body: Only include if the diff introduces high complexity.
                    - Each body array item must be exactly one plain-text line no longer than 72 characters.
                    - DO NOT describe "how" the code changed (the diff shows that); describe "why" or "what" the high-level impact is.
                    - Do not include markdown headers, code fences, or any text outside the JSON object.

                    ## Filtering Criteria:
                    Ignore the following for the detailed explanation (include them in the subject only):
                    - Variable/function renaming.
                    - Whitespace, formatting, or linting fixes.
                    - Adding/removing logs or print statements.
                    - Trivial logic tweaks or one-line fixes.

                    If the change is simple, output ONLY the subject line.
                    </goal>
                    """;
        }

        return List.of(
                new SystemMessage(systemIntro(formatInstructions)), new UserMessage(context + "\n\n" + instructions));
    }

    private String systemIntro(String formatInstructions) {
        return """
               You are an expert software engineer that generates Git commit messages based on provided diffs.
               Review the provided context and diffs which are about to be committed to a git repo.
               Review the diffs carefully.

               General guidelines:
               - Use the imperative mood (e.g., "Add feature" not "Added feature" or "Adding feature").
               - No trailing period on the subject line.
               - Never reveal your reasoning or mention the prompt.
               - Return data only, not commentary.

               Follow these format preferences:
               %s
               """
                .formatted(formatInstructions);
    }

    public String preprocessUnifiedDiff(String diffTxt, int fileCount, int linesPerFile) {
        var unifiedOpt = ContentDiffUtils.parseUnifiedDiff(diffTxt);
        if (unifiedOpt.isEmpty()) {
            return "";
        }

        var files = unifiedOpt.get().getFiles();

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
                if (!includedAtLeastOne && size > linesPerFile) {
                    // Include the largest hunk even if it exceeds the limit
                    output.addAll(lines);
                    includedAtLeastOne = true;
                    break;
                }
                if (added + size <= linesPerFile) {
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
}
