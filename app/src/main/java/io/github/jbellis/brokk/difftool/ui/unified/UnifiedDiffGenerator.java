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

        System.err.println("=== DIFF_DEBUG: generateStandardContextDiff CALLED ===");

        // Use UnifiedDiffUtils to generate standard unified diff
        var unifiedLines = UnifiedDiffUtils.generateUnifiedDiff(
                leftSource.title(), rightSource.title(), leftLines, patch, STANDARD_CONTEXT_LINES);

        System.err.println("DIFF_DEBUG_START:RAW_LINES");
        for (int i = 0; i < unifiedLines.size(); i++) {
            System.err.println("DIFF_DEBUG:RAW[" + i + "]: " + unifiedLines.get(i));
        }
        System.err.println("DIFF_DEBUG_END:RAW_LINES");

        var diffLines = new ArrayList<UnifiedDiffDocument.DiffLine>();
        int leftLineNum = 0;
        int rightLineNum = 0;
        int lastLeftEnd = -1;
        int lastRightEnd = -1;
        int hunkLeftCount = 0;
        int hunkRightCount = 0;
        boolean isCurrentHunkNewFile = false;

        for (var line : unifiedLines) {
            if (line.startsWith("---") || line.startsWith("+++")) {
                // Skip file headers
            } else if (line.startsWith("@@")) {
                // Hunk header - extract line numbers and counts
                var hunkInfo = parseHunkHeaderWithCounts(line);
                int newLeftStart = hunkInfo[0];
                int newRightStart = hunkInfo[1];
                hunkLeftCount = hunkInfo[2];
                hunkRightCount = hunkInfo[3];
                isCurrentHunkNewFile = hunkInfo[4] == 1;

                logger.debug(
                        "Parsed hunk header '{}' -> leftStart={}, rightStart={}, isNewFile={}",
                        line,
                        newLeftStart,
                        newRightStart,
                        isCurrentHunkNewFile);
                System.err.println("DIFF_DEBUG:HUNK_HEADER: " + line + " -> left=" + newLeftStart + ", right="
                        + newRightStart + ", isNewFile=" + isCurrentHunkNewFile);

                // Check if there's a gap between this hunk and the previous one
                if (lastLeftEnd > 0 && lastRightEnd > 0) {
                    int leftGap = newLeftStart - lastLeftEnd;
                    int rightGap = newRightStart - lastRightEnd;

                    System.err.println("DIFF_DEBUG:GAP_CHECK: lastLeftEnd=" + lastLeftEnd + ", lastRightEnd="
                            + lastRightEnd + ", newLeftStart="
                            + newLeftStart + ", newRightStart=" + newRightStart + ", leftGap="
                            + leftGap + ", rightGap=" + rightGap);

                    // Insert OMITTED_LINES for any significant gap (more than 1 line on either side)
                    // This handles cases where there are large additions/deletions causing line number jumps
                    if (leftGap > 1 || rightGap > 1) {
                        // Calculate omitted lines more intelligently:
                        // - For pure additions (leftGap = 1, rightGap > 1), show right gap
                        // - For pure deletions (leftGap > 1, rightGap = 1), show left gap
                        // - For mixed changes, show the larger gap
                        int omittedCount;
                        String gapType;

                        if (leftGap == 1 && rightGap > 1) {
                            omittedCount = rightGap - 1;
                            gapType = "addition gap";
                        } else if (rightGap == 1 && leftGap > 1) {
                            omittedCount = leftGap - 1;
                            gapType = "deletion gap";
                        } else {
                            omittedCount = Math.max(leftGap - 1, rightGap - 1);
                            gapType = "mixed gap";
                        }

                        String omittedText = String.format("... %d lines omitted ...", omittedCount);
                        logger.debug(
                                "Inserting OMITTED_LINES: '{}' (leftGap={}, rightGap={}, type={})",
                                omittedText,
                                leftGap,
                                rightGap,
                                gapType);
                        System.err.println("DIFF_DEBUG:OMITTED_LINES_INSERTED: " + omittedText + " (leftGap=" + leftGap
                                + ", rightGap=" + rightGap + ", type=" + gapType + ")");
                        diffLines.add(new UnifiedDiffDocument.DiffLine(
                                UnifiedDiffDocument.LineType.OMITTED_LINES, omittedText, -1, -1, false));
                    }
                }

                leftLineNum = newLeftStart;
                rightLineNum = newRightStart;

                // Update the end positions for the next gap calculation
                // These represent where this hunk will end
                lastLeftEnd = newLeftStart + hunkLeftCount;
                lastRightEnd = newRightStart + hunkRightCount;
                System.err.println(
                        "DIFF_DEBUG:HUNK_END_POSITIONS: lastLeftEnd=" + lastLeftEnd + ", lastRightEnd=" + lastRightEnd);

                diffLines.add(
                        new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.HEADER, line, -1, -1, false));
            } else if (line.startsWith("+")) {
                // Addition - increment right line number
                logger.trace(
                        "Addition line: '{}' -> right={}",
                        line.substring(0, Math.min(20, line.length())),
                        rightLineNum);
                System.err.println("DIFF_DEBUG:ADDITION: rightLineNum=" + rightLineNum + ", content='"
                        + line.substring(0, Math.min(30, line.length())) + "'");
                diffLines.add(new UnifiedDiffDocument.DiffLine(
                        UnifiedDiffDocument.LineType.ADDITION, line, -1, rightLineNum, true));
                rightLineNum++;
                System.err.println("DIFF_DEBUG:ADDITION_AFTER: rightLineNum=" + rightLineNum);
            } else if (line.startsWith("-")) {
                // Deletion - increment left line number
                // For new files, we don't show left line numbers since there's no original file
                int displayLeftLineNum = isCurrentHunkNewFile ? 0 : leftLineNum;
                logger.trace(
                        "Deletion line: '{}' -> left={} (isNewFile={}, displayLeft={})",
                        line.substring(0, Math.min(20, line.length())),
                        leftLineNum,
                        isCurrentHunkNewFile,
                        displayLeftLineNum);
                System.err.println("DIFF_DEBUG:DELETION: leftLineNum=" + leftLineNum + ", isNewFile="
                        + isCurrentHunkNewFile + ", displayLeftLineNum=" + displayLeftLineNum + ", content='"
                        + line.substring(0, Math.min(30, line.length())) + "'");
                diffLines.add(new UnifiedDiffDocument.DiffLine(
                        UnifiedDiffDocument.LineType.DELETION, line, displayLeftLineNum, -1, false));
                leftLineNum++;
                System.err.println("DIFF_DEBUG:DELETION_AFTER: leftLineNum=" + leftLineNum);
            } else if (line.startsWith(" ")) {
                // Context - increment both line numbers
                logger.trace(
                        "Context line: '{}' -> left={}, right={}",
                        line.substring(0, Math.min(20, line.length())),
                        leftLineNum,
                        rightLineNum);
                System.err.println("DIFF_DEBUG:CONTEXT: leftLineNum=" + leftLineNum + ", rightLineNum=" + rightLineNum
                        + ", content='" + line.substring(0, Math.min(30, line.length())) + "'");
                diffLines.add(new UnifiedDiffDocument.DiffLine(
                        UnifiedDiffDocument.LineType.CONTEXT, line, leftLineNum, rightLineNum, true));
                leftLineNum++;
                rightLineNum++;
                System.err.println(
                        "DIFF_DEBUG:CONTEXT_AFTER: leftLineNum=" + leftLineNum + ", rightLineNum=" + rightLineNum);
            }
        }

        System.err.println("DIFF_DEBUG_START:FINAL_DIFFLINES");
        for (int i = 0; i < diffLines.size(); i++) {
            var diffLine = diffLines.get(i);
            System.err.println("DIFF_DEBUG:DIFFLINE[" + i + "]: " + diffLine.getType() + " (left="
                    + diffLine.getLeftLineNumber() + ", right=" + diffLine.getRightLineNumber() + ") - '"
                    + diffLine.getContent()
                            .substring(0, Math.min(50, diffLine.getContent().length())) + "'");
        }
        System.err.println("DIFF_DEBUG_END:FINAL_DIFFLINES");

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

        System.err.println("DIFF_DEBUG_START:RAW_LINES_PATCH");
        for (int i = 0; i < unifiedLines.size(); i++) {
            System.err.println("DIFF_DEBUG:RAW[" + i + "]: " + unifiedLines.get(i));
        }
        System.err.println("DIFF_DEBUG_END:RAW_LINES_PATCH");

        var diffLines = new ArrayList<UnifiedDiffDocument.DiffLine>();
        int leftLineNum = 0;
        int rightLineNum = 0;
        int lastLeftEnd = -1;
        int lastRightEnd = -1;
        int hunkLeftCount = 0;
        int hunkRightCount = 0;
        boolean isCurrentHunkNewFile = false;

        for (var line : unifiedLines) {
            if (line.startsWith("---") || line.startsWith("+++")) {
                // Skip file headers
            } else if (line.startsWith("@@")) {
                // Hunk header - extract line numbers and counts
                var hunkInfo = parseHunkHeaderWithCounts(line);
                int newLeftStart = hunkInfo[0];
                int newRightStart = hunkInfo[1];
                hunkLeftCount = hunkInfo[2];
                hunkRightCount = hunkInfo[3];
                isCurrentHunkNewFile = hunkInfo[4] == 1;

                logger.debug(
                        "Parsed hunk header '{}' -> leftStart={}, rightStart={}, isNewFile={}",
                        line,
                        newLeftStart,
                        newRightStart,
                        isCurrentHunkNewFile);
                System.err.println("DIFF_DEBUG:HUNK_HEADER: " + line + " -> left=" + newLeftStart + ", right="
                        + newRightStart + ", isNewFile=" + isCurrentHunkNewFile);

                // Check if there's a gap between this hunk and the previous one
                if (lastLeftEnd > 0 && lastRightEnd > 0) {
                    int leftGap = newLeftStart - lastLeftEnd;
                    int rightGap = newRightStart - lastRightEnd;

                    System.err.println("DIFF_DEBUG:GAP_CHECK: lastLeftEnd=" + lastLeftEnd + ", lastRightEnd="
                            + lastRightEnd + ", newLeftStart="
                            + newLeftStart + ", newRightStart=" + newRightStart + ", leftGap="
                            + leftGap + ", rightGap=" + rightGap);

                    // Insert OMITTED_LINES for any significant gap (more than 1 line on either side)
                    // This handles cases where there are large additions/deletions causing line number jumps
                    if (leftGap > 1 || rightGap > 1) {
                        // Calculate omitted lines more intelligently:
                        // - For pure additions (leftGap = 1, rightGap > 1), show right gap
                        // - For pure deletions (leftGap > 1, rightGap = 1), show left gap
                        // - For mixed changes, show the larger gap
                        int omittedCount;
                        String gapType;

                        if (leftGap == 1 && rightGap > 1) {
                            omittedCount = rightGap - 1;
                            gapType = "addition gap";
                        } else if (rightGap == 1 && leftGap > 1) {
                            omittedCount = leftGap - 1;
                            gapType = "deletion gap";
                        } else {
                            omittedCount = Math.max(leftGap - 1, rightGap - 1);
                            gapType = "mixed gap";
                        }

                        String omittedText = String.format("... %d lines omitted ...", omittedCount);
                        logger.debug(
                                "Inserting OMITTED_LINES: '{}' (leftGap={}, rightGap={}, type={})",
                                omittedText,
                                leftGap,
                                rightGap,
                                gapType);
                        System.err.println("DIFF_DEBUG:OMITTED_LINES_INSERTED: " + omittedText + " (leftGap=" + leftGap
                                + ", rightGap=" + rightGap + ", type=" + gapType + ")");
                        diffLines.add(new UnifiedDiffDocument.DiffLine(
                                UnifiedDiffDocument.LineType.OMITTED_LINES, omittedText, -1, -1, false));
                    }
                }

                leftLineNum = newLeftStart;
                rightLineNum = newRightStart;

                // Update the end positions for the next gap calculation
                // These represent where this hunk will end
                lastLeftEnd = newLeftStart + hunkLeftCount;
                lastRightEnd = newRightStart + hunkRightCount;
                System.err.println(
                        "DIFF_DEBUG:HUNK_END_POSITIONS: lastLeftEnd=" + lastLeftEnd + ", lastRightEnd=" + lastRightEnd);

                diffLines.add(
                        new UnifiedDiffDocument.DiffLine(UnifiedDiffDocument.LineType.HEADER, line, -1, -1, false));
            } else if (line.startsWith("+")) {
                // Addition - increment right line number
                logger.trace(
                        "Addition line: '{}' -> right={}",
                        line.substring(0, Math.min(20, line.length())),
                        rightLineNum);
                System.err.println("DIFF_DEBUG:ADDITION: rightLineNum=" + rightLineNum + ", content='"
                        + line.substring(0, Math.min(30, line.length())) + "'");
                diffLines.add(new UnifiedDiffDocument.DiffLine(
                        UnifiedDiffDocument.LineType.ADDITION, line, -1, rightLineNum, true));
                rightLineNum++;
                System.err.println("DIFF_DEBUG:ADDITION_AFTER: rightLineNum=" + rightLineNum);
            } else if (line.startsWith("-")) {
                // Deletion - increment left line number
                // For new files, we don't show left line numbers since there's no original file
                int displayLeftLineNum = isCurrentHunkNewFile ? 0 : leftLineNum;
                logger.trace(
                        "Deletion line: '{}' -> left={} (isNewFile={}, displayLeft={})",
                        line.substring(0, Math.min(20, line.length())),
                        leftLineNum,
                        isCurrentHunkNewFile,
                        displayLeftLineNum);
                System.err.println("DIFF_DEBUG:DELETION: leftLineNum=" + leftLineNum + ", isNewFile="
                        + isCurrentHunkNewFile + ", displayLeftLineNum=" + displayLeftLineNum + ", content='"
                        + line.substring(0, Math.min(30, line.length())) + "'");
                diffLines.add(new UnifiedDiffDocument.DiffLine(
                        UnifiedDiffDocument.LineType.DELETION, line, displayLeftLineNum, -1, false));
                leftLineNum++;
                System.err.println("DIFF_DEBUG:DELETION_AFTER: leftLineNum=" + leftLineNum);
            } else if (line.startsWith(" ")) {
                // Context - increment both line numbers
                logger.trace(
                        "Context line: '{}' -> left={}, right={}",
                        line.substring(0, Math.min(20, line.length())),
                        leftLineNum,
                        rightLineNum);
                System.err.println("DIFF_DEBUG:CONTEXT: leftLineNum=" + leftLineNum + ", rightLineNum=" + rightLineNum
                        + ", content='" + line.substring(0, Math.min(30, line.length())) + "'");
                diffLines.add(new UnifiedDiffDocument.DiffLine(
                        UnifiedDiffDocument.LineType.CONTEXT, line, leftLineNum, rightLineNum, true));
                leftLineNum++;
                rightLineNum++;
                System.err.println(
                        "DIFF_DEBUG:CONTEXT_AFTER: leftLineNum=" + leftLineNum + ", rightLineNum=" + rightLineNum);
            }
        }

        System.err.println("DIFF_DEBUG_START:FINAL_DIFFLINES_PATCH");
        for (int i = 0; i < diffLines.size(); i++) {
            var diffLine = diffLines.get(i);
            System.err.println("DIFF_DEBUG:DIFFLINE[" + i + "]: " + diffLine.getType() + " (left="
                    + diffLine.getLeftLineNumber() + ", right=" + diffLine.getRightLineNumber() + ") - '"
                    + diffLine.getContent()
                            .substring(0, Math.min(50, diffLine.getContent().length())) + "'");
        }
        System.err.println("DIFF_DEBUG_END:FINAL_DIFFLINES_PATCH");

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
     * Parse hunk header to extract starting line numbers and counts. Format: @@ -start1,count1 +start2,count2 @@
     *
     * <p>For new files, the format is: @@ -0,0 +1,count2 @@ For deleted files, the format is: @@ -1,count1 +0,0 @@
     *
     * @param hunkHeader The hunk header line
     * @return Array of [leftStart, rightStart, leftCount, rightCount, isNewFile] (1-based, except isNewFile is 0/1)
     */
    private static int[] parseHunkHeaderWithCounts(String hunkHeader) {
        try {
            // Extract the part between @@ and @@
            var headerContent = hunkHeader.substring(3, hunkHeader.lastIndexOf(" @@"));
            var parts = headerContent.split(" ", -1);

            // Parse left side (-start,count)
            var leftPart = parts[0].substring(1); // Remove '-'
            int leftStart, leftCount;
            if (leftPart.contains(",")) {
                var leftSplit = leftPart.split(",", -1);
                leftStart = Integer.parseInt(leftSplit[0]);
                leftCount = Integer.parseInt(leftSplit[1]);
            } else {
                leftStart = Integer.parseInt(leftPart);
                leftCount = 1; // Default when no count specified
            }

            // Parse right side (+start,count)
            var rightPart = parts[1].substring(1); // Remove '+'
            int rightStart, rightCount;
            if (rightPart.contains(",")) {
                var rightSplit = rightPart.split(",", -1);
                rightStart = Integer.parseInt(rightSplit[0]);
                rightCount = Integer.parseInt(rightSplit[1]);
            } else {
                rightStart = Integer.parseInt(rightPart);
                rightCount = 1; // Default when no count specified
            }

            // Detect new file pattern: @@ -0,0 +1,X @@
            int isNewFile = (leftStart == 0 && leftCount == 0) ? 1 : 0;

            System.err.println("DIFF_DEBUG:PARSE_HUNK: " + hunkHeader + " -> leftStart="
                    + leftStart + ", leftCount=" + leftCount + ", rightStart="
                    + rightStart + ", rightCount=" + rightCount + ", isNewFile="
                    + isNewFile);

            return new int[] {leftStart, rightStart, leftCount, rightCount, isNewFile};
        } catch (Exception e) {
            logger.warn("Failed to parse hunk header: {}", hunkHeader, e);
            return new int[] {1, 1, 1, 1, 0}; // Default fallback
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
     * Normalize a line by removing all types of line ending characters. This uses the \R pattern to catch all Unicode
     * line breaks.
     *
     * @param line The line to normalize
     * @return The line with all line endings stripped
     */
    private static String normalizeLineEndings(String line) {
        if (line == null) {
            return "";
        }
        // Use \R to match any Unicode line break sequence
        return line.replaceAll("\\R", "");
    }

    /**
     * Generate plain text unified diff content from a JMDiffNode (for pure syntax highlighting). This method produces
     * simple text output without complex DiffLine objects.
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
                var rawLeftLines = leftBufferNode.getDocument().getLineList();
                leftTitle = leftBufferNode.getDocument().getName();
                logger.debug(
                        "Left source has {} lines, first few: {}",
                        rawLeftLines.size(),
                        rawLeftLines.stream()
                                .limit(3)
                                .map(line -> "'" + line.replace("\n", "\\n") + "'")
                                .toList());

                // Normalize lines to remove any embedded newlines that could cause spacing issues
                leftLines = rawLeftLines.stream()
                        .map(UnifiedDiffGenerator::normalizeLineEndings)
                        .toList();
                logger.debug(
                        "After normalization, first few: {}",
                        leftLines.stream()
                                .limit(3)
                                .map(line -> "'" + line + "'")
                                .toList());
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

    /** Generate standard context plain text using comprehensive line normalization. */
    private static String generateStandardContextPlainText(
            List<String> leftLines,
            Patch<String> patch,
            @SuppressWarnings("unused") String leftTitle,
            @SuppressWarnings("unused") String rightTitle) {

        var textBuilder = new StringBuilder();
        var deltas = patch.getDeltas();

        logger.debug("Generating standard context diff with {} deltas", deltas.size());

        for (var delta : deltas) {
            // Add hunk header
            var hunkHeader = String.format(
                    "@@ -%d,%d +%d,%d @@",
                    delta.getSource().getPosition() + 1,
                    delta.getSource().size(),
                    delta.getTarget().getPosition() + 1,
                    delta.getTarget().size());
            textBuilder.append(hunkHeader).append('\n');

            // Add context lines before the change (3 lines)
            int contextStart = Math.max(0, delta.getSource().getPosition() - STANDARD_CONTEXT_LINES);
            for (int i = contextStart; i < delta.getSource().getPosition(); i++) {
                if (i < leftLines.size()) {
                    // Context lines are already normalized from leftLines processing
                    textBuilder.append(' ').append(leftLines.get(i)).append('\n');
                }
            }

            // Add deleted lines - NORMALIZE THESE (they come from patch and may have line endings)
            for (var deletedLine : delta.getSource().getLines()) {
                var normalizedDeletedLine = normalizeLineEndings(deletedLine);
                logger.trace(
                        "Normalized deleted line: '{}' -> '{}'",
                        deletedLine.replace("\n", "\\n"),
                        normalizedDeletedLine);
                textBuilder.append('-').append(normalizedDeletedLine).append('\n');
            }

            // Add added lines - NORMALIZE THESE (they come from patch and may have line endings)
            for (var addedLine : delta.getTarget().getLines()) {
                var normalizedAddedLine = normalizeLineEndings(addedLine);
                logger.trace(
                        "Normalized added line: '{}' -> '{}'", addedLine.replace("\n", "\\n"), normalizedAddedLine);
                textBuilder.append('+').append(normalizedAddedLine).append('\n');
            }

            // Add context lines after the change (3 lines)
            int sourceEnd = delta.getSource().getPosition() + delta.getSource().size();
            int contextEnd = Math.min(leftLines.size(), sourceEnd + STANDARD_CONTEXT_LINES);
            for (int i = sourceEnd; i < contextEnd; i++) {
                if (i < leftLines.size()) {
                    // Context lines are already normalized from leftLines processing
                    textBuilder.append(' ').append(leftLines.get(i)).append('\n');
                }
            }
        }

        // Remove trailing newline if present
        if (textBuilder.length() > 0 && textBuilder.charAt(textBuilder.length() - 1) == '\n') {
            textBuilder.setLength(textBuilder.length() - 1);
        }

        logger.debug(
                "Generated clean unified diff with {} characters, {} lines",
                textBuilder.length(),
                textBuilder.toString().split("\n").length);
        return textBuilder.toString();
    }

    /** Generate full context plain text showing all lines between changes. */
    private static String generateFullContextPlainText(List<String> leftLines, Patch<String> patch) {
        var textBuilder = new StringBuilder();
        var deltas = patch.getDeltas();

        logger.debug("Generating full context diff with {} deltas", deltas.size());

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

            // Add deleted lines - NORMALIZE THESE (they come from patch and may have line endings)
            for (var deletedLine : delta.getSource().getLines()) {
                var normalizedDeletedLine = normalizeLineEndings(deletedLine);
                logger.trace(
                        "Normalized deleted line: '{}' -> '{}'",
                        deletedLine.replace("\n", "\\n"),
                        normalizedDeletedLine);
                textBuilder.append("-");
                textBuilder.append(normalizedDeletedLine);
                textBuilder.append('\n');
                leftIndex++;
            }

            // Add added lines - NORMALIZE THESE (they come from patch and may have line endings)
            for (var addedLine : delta.getTarget().getLines()) {
                var normalizedAddedLine = normalizeLineEndings(addedLine);
                logger.trace(
                        "Normalized added line: '{}' -> '{}'", addedLine.replace("\n", "\\n"), normalizedAddedLine);
                textBuilder.append("+");
                textBuilder.append(normalizedAddedLine);
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

        logger.debug("Generated full context diff with {} characters", textBuilder.length());
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
