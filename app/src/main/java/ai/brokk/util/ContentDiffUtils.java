package ai.brokk.util;

import static java.nio.charset.StandardCharsets.UTF_8;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import ai.brokk.git.IGitRepo;
import ai.brokk.project.MainProject;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

public class ContentDiffUtils {
    public static final int STANDARD_CONTEXT_LINES = 3;

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
        return computeDiffResult(oldContent, newContent, oldName, newName, STANDARD_CONTEXT_LINES);
    }

    /**
     * Compute a unified diff for code review with dynamic context lines and hunk grouping.
     * <p>
     * This method differs from {@link #computeDiffResult} in that it:
     * <ul>
     *   <li>Calculates context size dynamically based on hunk size (larger changes get more context).</li>
     *   <li>Groups nearby deltas into single hunks to minimize header overhead and improve readability.</li>
     *   <li>Optimizes the merge/split decision by comparing gap size against header and context costs.</li>
     * </ul>
     */
    public static DiffComputationResult computeReviewDiffResult(
            @Nullable IAnalyzer analyzer,
            @Nullable ProjectFile currentFile,
            String oldContent,
            String newContent,
            @Nullable String oldName,
            @Nullable String newName) {
        var oldLines = toLines(oldContent);
        var newLines = toLines(newContent);

        if (newContent.isEmpty() && !oldContent.isEmpty()) {
            String label = oldName != null ? oldName : "unknown";
            String diff =
                    """
                    --- a/%s
                    +++ /dev/null
                    [HARNESS NOTE: %s was removed]"""
                            .formatted(label, label);
            return new DiffComputationResult(diff, 0, oldLines.size());
        }

        Patch<String> patch = DiffUtils.diff(oldLines, newLines, ContentDiffUtils::reviewLinesEqualIgnoringWhitespace);
        if (patch.getDeltas().isEmpty()) {
            return new DiffComputationResult("", 0, 0);
        }

        List<AbstractDelta<String>> deltas = patch.getDeltas();
        int totalAdded = 0;
        int totalDeleted = 0;

        // Pass 1: Group deltas using the cost-based threshold.
        // Cost of a hunk header (~25 bytes) normalized to roughly 1.0 line of context.
        final double HEADER_COST_LINES = 1.0;

        List<List<AbstractDelta<String>>> groups = new ArrayList<>();
        List<AbstractDelta<String>> currentGroup = new ArrayList<>();
        currentGroup.add(deltas.getFirst());
        groups.add(currentGroup);

        for (int i = 1; i < deltas.size(); i++) {
            AbstractDelta<String> prev = deltas.get(i - 1);
            AbstractDelta<String> next = deltas.get(i);

            int gap = next.getSource().getPosition()
                    - (prev.getSource().getPosition() + prev.getSource().size());
            int currentGroupChangeSize = currentGroup.stream()
                    .mapToInt(d -> d.getSource().size() + d.getTarget().size())
                    .sum();

            int basePotentialContext = Math.max(1, Math.min(10, (int) Math.ceil(currentGroupChangeSize / 20.0)));
            int mergePotentialContext = isSameEnclosingFunction(analyzer, currentFile, prev, next)
                    ? (basePotentialContext * 5)
                    : basePotentialContext;

            // Merge if the gap is smaller than the overhead of starting a new hunk.
            // Split cost = header_cost + context_before + context_after.
            if (gap <= HEADER_COST_LINES + (2 * mergePotentialContext)) {
                currentGroup.add(next);
            } else {
                currentGroup = new ArrayList<>();
                currentGroup.add(next);
                groups.add(currentGroup);
            }
        }

        // Pass 2: Render groups
        List<String> unifiedDiffLines = new ArrayList<>();
        unifiedDiffLines.add("--- " + (oldName == null ? "/dev/null" : "a/" + oldName));
        unifiedDiffLines.add("+++ " + (newName == null ? "/dev/null" : "b/" + newName));

        for (List<AbstractDelta<String>> group : groups) {
            int groupAdded = 0;
            int groupDeleted = 0;
            Patch<String> groupPatch = new Patch<>();
            for (AbstractDelta<String> delta : group) {
                groupPatch.addDelta(delta);
                groupAdded += delta.getTarget().size();
                groupDeleted += delta.getSource().size();
            }
            totalAdded += groupAdded;
            totalDeleted += groupDeleted;

            int groupChangeSize = groupAdded + groupDeleted;
            int desiredContextLines = Math.max(1, Math.min(10, (int) Math.ceil(groupChangeSize / 20.0)));

            unifiedDiffLines.addAll(renderGroupAsSingleHunk(oldLines, group, groupPatch, desiredContextLines));
        }

        List<String> finalDiffLines = withMethodNamesInHunkHeaders(analyzer, currentFile, unifiedDiffLines);
        return new DiffComputationResult(String.join("\n", finalDiffLines), totalAdded, totalDeleted);
    }

    private static final Pattern UNIFIED_HUNK_HEADER =
            Pattern.compile("^@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@(.*)$");

    /**
     * Matches the custom three-@@ hunk header format produced by rewriteHunkHeaderWithMethodName:
     * {@code @@ -old,len @@ +new,len @@ methodName}
     */
    private static final Pattern CUSTOM_HUNK_HEADER =
            Pattern.compile("^@@\\s+-(\\d+)(?:,(\\d+))?\\s+@@\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@.*$");

    private static List<String> renderGroupAsSingleHunk(
            List<String> oldLines,
            List<AbstractDelta<String>> group,
            Patch<String> groupPatch,
            int desiredContextLines) {
        int requiredContextLines = contextLinesToForceSingleHunk(group);
        int renderContextLines = Math.max(desiredContextLines, requiredContextLines);

        List<String> diffLines = generateGroupDiffLines(oldLines, groupPatch, renderContextLines);
        if (countHunks(diffLines) != 1) {
            diffLines = generateGroupDiffLines(oldLines, groupPatch, oldLines.size());
        }

        if (countHunks(diffLines) != 1) {
            return generateGroupDiffLines(oldLines, groupPatch, desiredContextLines);
        }

        return trimSingleHunkContext(diffLines, desiredContextLines);
    }

    private static List<String> generateGroupDiffLines(
            List<String> oldLines, Patch<String> groupPatch, int contextLines) {
        return UnifiedDiffUtils.generateUnifiedDiff("", "", oldLines, groupPatch, contextLines).stream()
                .filter(line -> !line.startsWith("---") && !line.startsWith("+++"))
                .toList();
    }

    private static int countHunks(List<String> diffLines) {
        return (int) diffLines.stream().filter(line -> line.startsWith("@@ ")).count();
    }

    private static int contextLinesToForceSingleHunk(List<AbstractDelta<String>> group) {
        int maxGap = 0;

        for (int i = 1; i < group.size(); i++) {
            AbstractDelta<String> prev = group.get(i - 1);
            AbstractDelta<String> next = group.get(i);

            int prevEnd = prev.getSource().getPosition() + prev.getSource().size();
            int gap = next.getSource().getPosition() - prevEnd;
            if (gap > maxGap) {
                maxGap = gap;
            }
        }

        if (maxGap <= 0) {
            return 0;
        }

        return (maxGap + 1) / 2;
    }

    private static List<String> trimSingleHunkContext(List<String> diffLines, int desiredContextLines) {
        int hunkHeaderIndex = -1;
        for (int i = 0; i < diffLines.size(); i++) {
            if (diffLines.get(i).startsWith("@@ ")) {
                hunkHeaderIndex = i;
                break;
            }
        }
        assert hunkHeaderIndex >= 0;

        String headerLine = diffLines.get(hunkHeaderIndex);
        List<String> body = new ArrayList<>(diffLines.subList(hunkHeaderIndex + 1, diffLines.size()));

        int firstChangeIndex = -1;
        int lastChangeIndex = -1;
        for (int i = 0; i < body.size(); i++) {
            char ch = body.get(i).isEmpty() ? '\0' : body.get(i).charAt(0);
            if (ch == '+' || ch == '-') {
                firstChangeIndex = i;
                break;
            }
        }
        for (int i = body.size() - 1; i >= 0; i--) {
            char ch = body.get(i).isEmpty() ? '\0' : body.get(i).charAt(0);
            if (ch == '+' || ch == '-' || ch == '\\') {
                lastChangeIndex = i;
                break;
            }
        }

        if (firstChangeIndex < 0 || lastChangeIndex < 0) {
            return diffLines;
        }

        int leadContext = 0;
        while (leadContext < firstChangeIndex && body.get(leadContext).startsWith(" ")) {
            leadContext++;
        }

        int trailContext = 0;
        while (trailContext < (body.size() - 1 - lastChangeIndex)
                && body.get(body.size() - 1 - trailContext).startsWith(" ")) {
            trailContext++;
        }

        int removeLeading = Math.max(0, leadContext - desiredContextLines);
        int removeTrailing = Math.max(0, trailContext - desiredContextLines);

        if (removeLeading == 0 && removeTrailing == 0) {
            return diffLines;
        }

        for (int i = 0; i < removeLeading; i++) {
            assert body.get(i).startsWith(" ");
        }
        for (int i = 0; i < removeTrailing; i++) {
            assert body.get(body.size() - 1 - i).startsWith(" ");
        }

        HunkHeader header = parseHunkHeader(headerLine);

        int newOldStart = header.oldStart() + removeLeading;
        int newNewStart = header.newStart() + removeLeading;
        int newOldLen = header.oldLen() - removeLeading - removeTrailing;
        int newNewLen = header.newLen() - removeLeading - removeTrailing;

        assert newOldLen >= 0;
        assert newNewLen >= 0;

        String newHeaderLine =
                "@@ -" + newOldStart + "," + newOldLen + " +" + newNewStart + "," + newNewLen + " @@" + header.suffix();

        int bodyEndExclusive = body.size() - removeTrailing;

        List<String> trimmed = new ArrayList<>(1 + (bodyEndExclusive - removeLeading));
        trimmed.add(newHeaderLine);
        trimmed.addAll(body.subList(removeLeading, bodyEndExclusive));
        return trimmed;
    }

    private record HunkHeader(int oldStart, int oldLen, int newStart, int newLen, String suffix) {}

    private static HunkHeader parseHunkHeader(String line) {
        Matcher m = UNIFIED_HUNK_HEADER.matcher(line);
        if (!m.matches()) {
            throw new IllegalArgumentException("Unrecognized unified diff hunk header: " + line);
        }

        int oldStart = Integer.parseInt(m.group(1));
        int oldLen = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
        int newStart = Integer.parseInt(m.group(3));
        int newLen = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
        String suffix = m.group(5);

        return new HunkHeader(oldStart, oldLen, newStart, newLen, suffix);
    }

    private static List<String> withMethodNamesInHunkHeaders(
            @Nullable IAnalyzer analyzer, @Nullable ProjectFile file, List<String> diffLines) {
        if (analyzer == null || file == null) {
            return diffLines;
        }

        return diffLines.stream()
                .map(line -> line.startsWith("@@ ") ? rewriteHunkHeaderWithMethodName(analyzer, file, line) : line)
                .toList();
    }

    private static String rewriteHunkHeaderWithMethodName(IAnalyzer analyzer, ProjectFile file, String headerLine) {
        HunkHeader header = parseHunkHeader(headerLine);

        String methodFromAnalyzer = analyzer.enclosingCodeUnit(
                        file, header.newStart(), header.newStart() + Math.max(1, header.newLen()) - 1)
                .filter(cu -> cu.kind() == CodeUnitType.FUNCTION)
                .map(CodeUnit::shortName)
                .orElse("");

        String methodName = !methodFromAnalyzer.isBlank()
                ? methodFromAnalyzer
                : header.suffix().trim();

        String sourceSpec = "-" + header.oldStart() + "," + header.oldLen();
        String targetSpec = "+" + header.newStart() + "," + header.newLen();

        if (methodName.isBlank()) {
            return headerLine;
        }
        return "@@ " + sourceSpec + " @@ " + targetSpec + " @@ " + methodName;
    }

    private static boolean isSameEnclosingFunction(
            @Nullable IAnalyzer analyzer,
            @Nullable ProjectFile file,
            AbstractDelta<String> a,
            AbstractDelta<String> b) {
        if (analyzer == null || file == null) {
            return false;
        }

        Optional<CodeUnit> aFn = enclosingFunctionOf(analyzer, file, a);
        if (aFn.isEmpty()) {
            return false;
        }

        Optional<CodeUnit> bFn = enclosingFunctionOf(analyzer, file, b);
        if (bFn.isEmpty()) {
            return false;
        }

        return aFn.get().equals(bFn.get());
    }

    private static Optional<CodeUnit> enclosingFunctionOf(
            IAnalyzer analyzer, ProjectFile file, AbstractDelta<String> delta) {
        int startLine = delta.getTarget().getPosition() + 1;
        int targetSize = delta.getTarget().size();
        int endLine = startLine + Math.max(1, targetSize) - 1;

        return analyzer.enclosingCodeUnit(file, startLine, endLine).filter(cu -> cu.kind() == CodeUnitType.FUNCTION);
    }

    /**
     * Compute a unified diff and change counts (added/deleted) between two strings using java-diff-utils.
     * <p>
     * This uses standard unified diff generation:
     * <ul>
     *   <li>Uses a fixed number of context lines for all hunks.</li>
     *   <li>Merges adjacent hunks if their context lines overlap.</li>
     * </ul>
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

        // Renders the full Patch in one pass; UnifiedDiffUtils will merge nearby deltas into a single hunk
        // when their context windows overlap.
        var diffLines = UnifiedDiffUtils.generateUnifiedDiff(
                oldName == null ? null : "a/" + oldName,
                newName == null ? null : "b/" + newName,
                oldLines,
                patch,
                contextLines);
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
        // and strip custom hunk headers back to standard format
        String processedDiff = stripCustomHunkHeaders(filterEmptyFileCreations(diffTxt));

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
     * Strips the custom three-@@ hunk header format (produced by {@link #rewriteHunkHeaderWithMethodName}) back to
     * standard unified diff format so that {@link UnifiedDiffReader} can parse it.
     * <p>
     * Custom format: {@code @@ -old,len @@ +new,len @@ methodName}
     * Standard format: {@code @@ -old,len +new,len @@}
     */
    private static String stripCustomHunkHeaders(String diffTxt) {
        return diffTxt.lines()
                .map(line -> {
                    Matcher m = CUSTOM_HUNK_HEADER.matcher(line);
                    if (!m.matches()) {
                        return line;
                    }
                    String oldStart = m.group(1);
                    String oldLen = m.group(2);
                    String newStart = m.group(3);
                    String newLen = m.group(4);
                    String oldSpec = oldLen != null ? oldStart + "," + oldLen : oldStart;
                    String newSpec = newLen != null ? newStart + "," + newLen : newStart;
                    return "@@ -" + oldSpec + " +" + newSpec + " @@";
                })
                .collect(Collectors.joining("\n"));
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

    private static boolean reviewLinesEqualIgnoringWhitespace(String a, String b) {
        return stripAllWhitespace(a).equals(stripAllWhitespace(b));
    }

    private static String stripAllWhitespace(String s) {
        return s.chars()
                .filter(ch -> !Character.isWhitespace(ch))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
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

    public static void main(String[] args) throws GitAPIException {
        String projectPath = ".";
        String mode = "standard";
        List<String> positionalArgs = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            if ("--project".equals(args[i]) && i + 1 < args.length) {
                projectPath = args[++i];
            } else if ("--mode".equals(args[i]) && i + 1 < args.length) {
                mode = args[++i];
            } else if (!args[i].startsWith("--")) {
                positionalArgs.add(args[i]);
            }
        }

        if (positionalArgs.isEmpty()) {
            System.err.println("Usage: ContentDiffUtils [--project <path>] [--mode standard|review] <commit-ish>");
            System.exit(1);
        }

        String revA = positionalArgs.getFirst();
        String revB = "HEAD";

        var project = new MainProject(Path.of(projectPath).toAbsolutePath());
        Language langHandle = Languages.aggregate(project.getAnalyzerLanguages());

        var analyzer = langHandle.loadAnalyzer(project, (completed, total, phase) -> {});
        GitRepo repo = (GitRepo) project.getRepo();
        String oldCommitId = repo.resolveToCommit(revA).name();
        String newCommitId = repo.resolveToCommit(revB).name();

        List<IGitRepo.ModifiedFile> modifiedFiles = repo.listFilesChangedBetweenCommits(oldCommitId, newCommitId);

        for (IGitRepo.ModifiedFile mf : modifiedFiles) {
            String oldContent = repo.getFileContent(oldCommitId, mf.file());
            String newContent = repo.getFileContent(newCommitId, mf.file());
            String fileName = mf.file().toString();

            DiffComputationResult result;
            if ("review".equalsIgnoreCase(mode)) {
                result = computeReviewDiffResult(analyzer, mf.file(), oldContent, newContent, fileName, fileName);
            } else {
                result = computeDiffResult(oldContent, newContent, fileName, fileName);
            }

            if (!result.diff().isEmpty()) {
                System.out.println(result.diff());
            }
        }
    }
}
