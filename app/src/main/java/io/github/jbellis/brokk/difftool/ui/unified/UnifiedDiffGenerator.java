package io.github.jbellis.brokk.difftool.ui.unified;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import io.github.jbellis.brokk.difftool.node.JMDiffNode;
import io.github.jbellis.brokk.difftool.ui.BufferSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Generates unified diff documents from buffer sources with support for both standard and full context modes. */
public class UnifiedDiffGenerator {
    private static final Logger logger = LogManager.getLogger(UnifiedDiffGenerator.class);
    private static final int STANDARD_CONTEXT_LINES = 3;

    /**
     * Generate a unified diff document from a JMDiffNode (preferred method). This uses the pre-processed diff
     * information from the existing diff engine.
     *
     * @param diffNode The JMDiffNode containing processed diff information
     * @param contextMode Context mode to use
     * @return Generated UnifiedDiffDocument
     */
    public static UnifiedDiffDocument generateFromDiffNode(
            JMDiffNode diffNode, UnifiedDiffDocument.ContextMode contextMode) {

        try {
            // Get the pre-processed patch from the diff node
            var patch = diffNode.getPatch();
            if (patch == null) {
                logger.warn("JMDiffNode {} has no patch - no differences detected", diffNode.getName());
                return new UnifiedDiffDocument(new ArrayList<>(), contextMode);
            }

            // Get the source content from buffer nodes
            var leftBufferNode = diffNode.getBufferNodeLeft();
            var rightBufferNode = diffNode.getBufferNodeRight();

            List<String> leftLines;
            List<String> rightLines;
            String leftTitle = "left";
            String rightTitle = "right";

            if (leftBufferNode != null) {
                leftLines = leftBufferNode.getDocument().getLineList();
                leftTitle = leftBufferNode.getDocument().getName();
            } else {
                leftLines = new ArrayList<>();
                leftTitle = "<empty>";
            }

            if (rightBufferNode != null) {
                rightLines = rightBufferNode.getDocument().getLineList();
                rightTitle = rightBufferNode.getDocument().getName();
            } else {
                rightLines = new ArrayList<>();
                rightTitle = "<empty>";
            }

            List<UnifiedDiffDocument.DiffLine> diffLines;
            if (contextMode == UnifiedDiffDocument.ContextMode.FULL_CONTEXT) {
                diffLines = generateFullContextFromPatch(leftLines, rightLines, patch);
            } else {
                diffLines = generateStandardContextFromPatch(leftLines, rightLines, patch, leftTitle, rightTitle);
            }

            return new UnifiedDiffDocument(diffLines, contextMode);

        } catch (Exception e) {
            logger.error("Failed to generate unified diff from JMDiffNode {}", diffNode.getName(), e);
            throw new RuntimeException("Failed to generate unified diff from JMDiffNode", e);
        }
    }

    /**
     * Generate a unified diff document from two buffer sources. Note: This method is retained for backward
     * compatibility but generateFromDiffNode() is preferred.
     *
     * @param leftSource Source for the left side (original)
     * @param rightSource Source for the right side (revised)
     * @param contextMode Context mode to use
     * @return Generated UnifiedDiffDocument
     */
    public static UnifiedDiffDocument generateUnifiedDiff(
            BufferSource leftSource, BufferSource rightSource, UnifiedDiffDocument.ContextMode contextMode) {

        try {
            var leftContent = getContentFromSource(leftSource);
            var rightContent = getContentFromSource(rightSource);

            var leftLines = splitIntoLines(leftContent);
            var rightLines = splitIntoLines(rightContent);

            var patch = DiffUtils.diff(leftLines, rightLines);

            List<UnifiedDiffDocument.DiffLine> diffLines;
            if (contextMode == UnifiedDiffDocument.ContextMode.FULL_CONTEXT) {
                diffLines = generateFullContextDiff(leftLines, rightLines, patch);
            } else {
                diffLines = generateStandardContextDiff(leftLines, rightLines, patch, leftSource, rightSource);
            }

            return new UnifiedDiffDocument(diffLines, contextMode);

        } catch (Exception e) {
            logger.error("Failed to generate unified diff for {} vs {}", leftSource.title(), rightSource.title(), e);
            throw new RuntimeException("Failed to generate unified diff", e);
        }
    }

    /** Generate standard context diff using difflib's UnifiedDiffUtils. */
    private static List<UnifiedDiffDocument.DiffLine> generateStandardContextDiff(
            List<String> leftLines,
            @SuppressWarnings("unused") List<String> rightLines,
            Patch<String> patch,
            BufferSource leftSource,
            BufferSource rightSource) {

        // Use UnifiedDiffUtils to generate standard unified diff
        var unifiedLines = UnifiedDiffUtils.generateUnifiedDiff(
                leftSource.title(), rightSource.title(), leftLines, patch, STANDARD_CONTEXT_LINES);

        var diffLines = new ArrayList<UnifiedDiffDocument.DiffLine>();
        int leftLineNum = 0;
        int rightLineNum = 0;

        for (var line : unifiedLines) {
            if (line.startsWith("---") || line.startsWith("+++")) {
                // Skip file headers
            } else if (line.startsWith("@@")) {
                // Hunk header - extract line numbers
                var lineNumbers = parseHunkHeader(line);
                leftLineNum = lineNumbers[0];
                rightLineNum = lineNumbers[1];

                diffLines.add(
                        new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.HEADER, line, -1, -1, false));
            } else if (line.startsWith("+")) {
                // Addition - increment right line number
                diffLines.add(new UnifiedDiffDocument.DiffLine(
                        UnifiedDiffDocument.LineType.ADDITION, line, -1, rightLineNum, true));
                rightLineNum++;
            } else if (line.startsWith("-")) {
                // Deletion - increment left line number
                diffLines.add(new UnifiedDiffDocument.DiffLine(
                        UnifiedDiffDocument.LineType.DELETION, line, leftLineNum, -1, false));
                leftLineNum++;
            } else if (line.startsWith(" ")) {
                // Context - increment both line numbers
                diffLines.add(new UnifiedDiffDocument.DiffLine(
                        UnifiedDiffDocument.LineType.CONTEXT, line, leftLineNum, rightLineNum, true));
                leftLineNum++;
                rightLineNum++;
            }
        }

        return diffLines;
    }

    /** Generate standard context diff using pre-processed patch from JMDiffNode. */
    private static List<UnifiedDiffDocument.DiffLine> generateStandardContextFromPatch(
            List<String> leftLines,
            @SuppressWarnings("unused") List<String> rightLines, // rightLines not needed - patch contains target lines
            Patch<String> patch,
            String leftTitle,
            String rightTitle) {

        // Use UnifiedDiffUtils to generate standard unified diff from the existing patch
        var unifiedLines =
                UnifiedDiffUtils.generateUnifiedDiff(leftTitle, rightTitle, leftLines, patch, STANDARD_CONTEXT_LINES);

        var diffLines = new ArrayList<UnifiedDiffDocument.DiffLine>();
        int leftLineNum = 0;
        int rightLineNum = 0;

        for (var line : unifiedLines) {
            if (line.startsWith("---") || line.startsWith("+++")) {
                // Skip file headers
            } else if (line.startsWith("@@")) {
                // Hunk header - extract line numbers
                var lineNumbers = parseHunkHeader(line);
                leftLineNum = lineNumbers[0];
                rightLineNum = lineNumbers[1];

                diffLines.add(
                        new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.HEADER, line, -1, -1, false));
            } else if (line.startsWith("+")) {
                // Addition - increment right line number
                diffLines.add(new UnifiedDiffDocument.DiffLine(
                        UnifiedDiffDocument.LineType.ADDITION, line, -1, rightLineNum, true));
                rightLineNum++;
            } else if (line.startsWith("-")) {
                // Deletion - increment left line number
                diffLines.add(new UnifiedDiffDocument.DiffLine(
                        UnifiedDiffDocument.LineType.DELETION, line, leftLineNum, -1, false));
                leftLineNum++;
            } else if (line.startsWith(" ")) {
                // Context - increment both line numbers
                diffLines.add(new UnifiedDiffDocument.DiffLine(
                        UnifiedDiffDocument.LineType.CONTEXT, line, leftLineNum, rightLineNum, true));
                leftLineNum++;
                rightLineNum++;
            }
        }

        return diffLines;
    }

    /** Generate full context diff using pre-processed patch from JMDiffNode. */
    private static List<UnifiedDiffDocument.DiffLine> generateFullContextFromPatch(
            List<String> leftLines, List<String> rightLines, Patch<String> patch) {

        var diffLines = new ArrayList<UnifiedDiffDocument.DiffLine>();
        var deltas = patch.getDeltas();

        int leftIndex = 0;
        int rightIndex = 0;

        for (var delta : deltas) {
            // Add context lines before this delta
            while (leftIndex < delta.getSource().getPosition()
                    || rightIndex < delta.getTarget().getPosition()) {

                if (leftIndex < leftLines.size()
                        && rightIndex < rightLines.size()
                        && leftLines.get(leftIndex).equals(rightLines.get(rightIndex))) {
                    // Context line - same in both files
                    var line = " " + leftLines.get(leftIndex);
                    diffLines.add(new UnifiedDiffDocument.DiffLine(
                            UnifiedDiffDocument.LineType.CONTEXT, line, leftIndex + 1, rightIndex + 1, true));
                    leftIndex++;
                    rightIndex++;
                } else {
                    // This shouldn't happen in a proper diff, but handle gracefully
                    break;
                }
            }

            // Add hunk header before delta
            var hunkHeader = String.format(
                    "@@ -%d,%d +%d,%d @@",
                    delta.getSource().getPosition() + 1,
                    delta.getSource().size(),
                    delta.getTarget().getPosition() + 1,
                    delta.getTarget().size());
            diffLines.add(
                    new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.HEADER, hunkHeader, -1, -1, false));

            // Add deleted lines
            for (var deletedLine : delta.getSource().getLines()) {
                diffLines.add(new UnifiedDiffDocument.DiffLine(
                        UnifiedDiffDocument.LineType.DELETION, "-" + deletedLine, leftIndex + 1, -1, false));
                leftIndex++;
            }

            // Add added lines
            for (var addedLine : delta.getTarget().getLines()) {
                diffLines.add(new UnifiedDiffDocument.DiffLine(
                        UnifiedDiffDocument.LineType.ADDITION, "+" + addedLine, -1, rightIndex + 1, true));
                rightIndex++;
            }
        }

        // Add remaining context lines after all deltas
        while (leftIndex < leftLines.size()
                && rightIndex < rightLines.size()
                && leftLines.get(leftIndex).equals(rightLines.get(rightIndex))) {
            var line = " " + leftLines.get(leftIndex);
            diffLines.add(new UnifiedDiffDocument.DiffLine(
                    UnifiedDiffDocument.LineType.CONTEXT, line, leftIndex + 1, rightIndex + 1, true));
            leftIndex++;
            rightIndex++;
        }

        return diffLines;
    }

    /** Generate full context diff showing all lines between changes. */
    private static List<UnifiedDiffDocument.DiffLine> generateFullContextDiff(
            List<String> leftLines, List<String> rightLines, Patch<String> patch) {

        var diffLines = new ArrayList<UnifiedDiffDocument.DiffLine>();
        var deltas = patch.getDeltas();

        int leftIndex = 0;
        int rightIndex = 0;

        for (var delta : deltas) {
            // Add context lines before this delta
            while (leftIndex < delta.getSource().getPosition()
                    || rightIndex < delta.getTarget().getPosition()) {

                if (leftIndex < leftLines.size()
                        && rightIndex < rightLines.size()
                        && leftLines.get(leftIndex).equals(rightLines.get(rightIndex))) {
                    // Context line - same in both files
                    var line = " " + leftLines.get(leftIndex);
                    diffLines.add(new UnifiedDiffDocument.DiffLine(
                            UnifiedDiffDocument.LineType.CONTEXT, line, leftIndex + 1, rightIndex + 1, true));
                    leftIndex++;
                    rightIndex++;
                } else {
                    // This shouldn't happen in a proper diff, but handle gracefully
                    break;
                }
            }

            // Add hunk header before delta
            var hunkHeader = String.format(
                    "@@ -%d,%d +%d,%d @@",
                    delta.getSource().getPosition() + 1,
                    delta.getSource().size(),
                    delta.getTarget().getPosition() + 1,
                    delta.getTarget().size());
            diffLines.add(
                    new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.HEADER, hunkHeader, -1, -1, false));

            // Add deleted lines
            for (var deletedLine : delta.getSource().getLines()) {
                diffLines.add(new UnifiedDiffDocument.DiffLine(
                        UnifiedDiffDocument.LineType.DELETION, "-" + deletedLine, leftIndex + 1, -1, false));
                leftIndex++;
            }

            // Add added lines
            for (var addedLine : delta.getTarget().getLines()) {
                diffLines.add(new UnifiedDiffDocument.DiffLine(
                        UnifiedDiffDocument.LineType.ADDITION, "+" + addedLine, -1, rightIndex + 1, true));
                rightIndex++;
            }
        }

        // Add remaining context lines after all deltas
        while (leftIndex < leftLines.size()
                && rightIndex < rightLines.size()
                && leftLines.get(leftIndex).equals(rightLines.get(rightIndex))) {
            var line = " " + leftLines.get(leftIndex);
            diffLines.add(new UnifiedDiffDocument.DiffLine(
                    UnifiedDiffDocument.LineType.CONTEXT, line, leftIndex + 1, rightIndex + 1, true));
            leftIndex++;
            rightIndex++;
        }

        return diffLines;
    }

    /**
     * Parse hunk header to extract starting line numbers. Format: @@ -start1,count1 +start2,count2 @@
     *
     * @param hunkHeader The hunk header line
     * @return Array of [leftStart, rightStart] (1-based)
     */
    private static int[] parseHunkHeader(String hunkHeader) {
        try {
            // Extract the part between @@ and @@
            var headerContent = hunkHeader.substring(3, hunkHeader.lastIndexOf(" @@"));
            var parts = headerContent.split(" ", -1);

            // Parse left side (-start,count)
            var leftPart = parts[0].substring(1); // Remove '-'
            var leftStart =
                    leftPart.contains(",") ? Integer.parseInt(leftPart.split(",", -1)[0]) : Integer.parseInt(leftPart);

            // Parse right side (+start,count)
            var rightPart = parts[1].substring(1); // Remove '+'
            var rightStart = rightPart.contains(",")
                    ? Integer.parseInt(rightPart.split(",", -1)[0])
                    : Integer.parseInt(rightPart);

            return new int[] {leftStart, rightStart};
        } catch (Exception e) {
            logger.warn("Failed to parse hunk header: {}", hunkHeader, e);
            return new int[] {1, 1}; // Default fallback
        }
    }

    /** Get content string from BufferSource, handling both FileSource and StringSource. */
    private static String getContentFromSource(BufferSource source) throws Exception {
        if (source instanceof BufferSource.StringSource stringSource) {
            return stringSource.content();
        } else if (source instanceof BufferSource.FileSource fileSource) {
            var file = fileSource.file();
            if (!file.exists() || !file.isFile()) {
                return "";
            }
            return java.nio.file.Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        } else {
            throw new IllegalArgumentException("Unsupported BufferSource type: " + source.getClass());
        }
    }

    /** Split content into lines, handling different line endings. */
    private static List<String> splitIntoLines(String content) {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }

        // Split on any line ending, preserving the original line content
        return Arrays.asList(content.split("\\R"));
    }

    /**
     * Generate plain text unified diff content from a JMDiffNode (for pure syntax highlighting).
     * This method produces simple text output without complex DiffLine objects.
     *
     * @param diffNode The JMDiffNode containing pre-processed diff information
     * @param contextMode Context mode to use (3-line or full context)
     * @return Plain text unified diff content ready for RSyntaxTextArea
     */
    public static String generatePlainTextFromDiffNode(
            JMDiffNode diffNode, UnifiedDiffDocument.ContextMode contextMode) {

        try {
            // Get the pre-processed patch from the diff node
            var patch = diffNode.getPatch();
            if (patch == null) {
                logger.warn("JMDiffNode {} has no patch - no differences detected", diffNode.getName());
                return ""; // Return empty string for no differences
            }

            // Get the source content from buffer nodes
            var leftBufferNode = diffNode.getBufferNodeLeft();
            var rightBufferNode = diffNode.getBufferNodeRight();

            List<String> leftLines;
            String leftTitle = "left";
            String rightTitle = "right";

            if (leftBufferNode != null) {
                leftLines = leftBufferNode.getDocument().getLineList();
                leftTitle = leftBufferNode.getDocument().getName();
            } else {
                leftLines = new ArrayList<>();
                leftTitle = "<empty>";
            }

            if (rightBufferNode != null) {
                rightTitle = rightBufferNode.getDocument().getName();
            } else {
                rightTitle = "<empty>";
            }

            // Generate plain text based on context mode
            if (contextMode == UnifiedDiffDocument.ContextMode.FULL_CONTEXT) {
                return generateFullContextPlainText(leftLines, patch);
            } else {
                return generateStandardContextPlainText(leftLines, patch, leftTitle, rightTitle);
            }

        } catch (Exception e) {
            logger.error("Failed to generate plain text unified diff from JMDiffNode {}", diffNode.getName(), e);
            throw new RuntimeException("Failed to generate plain text unified diff from JMDiffNode", e);
        }
    }

    /**
     * Generate standard context plain text using difflib's UnifiedDiffUtils.
     */
    private static String generateStandardContextPlainText(
            List<String> leftLines, Patch<String> patch, String leftTitle, String rightTitle) {

        // Use UnifiedDiffUtils to generate standard unified diff from the existing patch
        var unifiedLines = UnifiedDiffUtils.generateUnifiedDiff(leftTitle, rightTitle, leftLines, patch, STANDARD_CONTEXT_LINES);

        var textBuilder = new StringBuilder();
        for (var line : unifiedLines) {
            // Skip file headers (--- and +++ lines)
            if (line.startsWith("---") || line.startsWith("+++")) {
                continue;
            }
            textBuilder.append(line);
            textBuilder.append('\n');
        }

        // Remove trailing newline if present
        if (textBuilder.length() > 0 && textBuilder.charAt(textBuilder.length() - 1) == '\n') {
            textBuilder.setLength(textBuilder.length() - 1);
        }

        return textBuilder.toString();
    }

    /**
     * Generate full context plain text showing all lines between changes.
     */
    private static String generateFullContextPlainText(List<String> leftLines, Patch<String> patch) {
        var textBuilder = new StringBuilder();
        var deltas = patch.getDeltas();

        int leftIndex = 0;

        for (var delta : deltas) {
            // Add context lines before this delta
            while (leftIndex < delta.getSource().getPosition()) {
                if (leftIndex < leftLines.size()) {
                    textBuilder.append(" "); // Context prefix
                    textBuilder.append(leftLines.get(leftIndex));
                    textBuilder.append('\n');
                    leftIndex++;
                } else {
                    break;
                }
            }

            // Add hunk header
            var hunkHeader = String.format(
                    "@@ -%d,%d +%d,%d @@",
                    delta.getSource().getPosition() + 1,
                    delta.getSource().size(),
                    delta.getTarget().getPosition() + 1,
                    delta.getTarget().size());
            textBuilder.append(hunkHeader);
            textBuilder.append('\n');

            // Add deleted lines
            for (var deletedLine : delta.getSource().getLines()) {
                textBuilder.append("-");
                textBuilder.append(deletedLine);
                textBuilder.append('\n');
                leftIndex++;
            }

            // Add added lines
            for (var addedLine : delta.getTarget().getLines()) {
                textBuilder.append("+");
                textBuilder.append(addedLine);
                textBuilder.append('\n');
            }
        }

        // Add remaining context lines after all deltas
        while (leftIndex < leftLines.size()) {
            textBuilder.append(" "); // Context prefix
            textBuilder.append(leftLines.get(leftIndex));
            textBuilder.append('\n');
            leftIndex++;
        }

        // Remove trailing newline if present
        if (textBuilder.length() > 0 && textBuilder.charAt(textBuilder.length() - 1) == '\n') {
            textBuilder.setLength(textBuilder.length() - 1);
        }

        return textBuilder.toString();
    }

    /** Create a simple unified diff for testing purposes. */
    public static UnifiedDiffDocument createTestDiff() {
        var diffLines = List.of(
                new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.HEADER, "@@ -1,4 +1,4 @@", -1, -1, false),
                new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.CONTEXT, " Line 1", 1, 1, true),
                new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.DELETION, "-Old Line 2", 2, -1, false),
                new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.ADDITION, "+New Line 2", -1, 2, true),
                new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.CONTEXT, " Line 3", 3, 3, true),
                new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.CONTEXT, " Line 4", 4, 4, true));

        return new UnifiedDiffDocument(diffLines, UnifiedDiffDocument.ContextMode.STANDARD_3_LINES);
    }
}
