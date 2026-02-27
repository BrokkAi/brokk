package ai.brokk.util;

import static java.lang.Math.max;
import static java.lang.Math.min;

import java.util.ArrayList;
import java.util.List;

public final class Lines {
    private Lines() {}

    public static HeadTail cap(
            String content, int maxLines, int topShown, int bottomShown, int maxCharsPerLine) {
        if (content.isEmpty()) {
            return HeadTail.full(content);
        }

        int length = content.length();

        // Do not use content.split("\\R", -1) here: for large files (e.g. logs) it allocates a String[]
        // for every line, which is expensive in both time and memory. Instead, scan once and keep only
        // offsets for the top/bottom lines we will include in the prompt.
        //
        // Limitation (accepted for now): this scanner intentionally recognizes only LF ('\n'), CR ('\r'),
        // and CRLF ("\r\n") as line breaks. It does NOT implement full Java "\\R" semantics (e.g. Unicode
        // line separators). totalLines/omitted is only a hint for the LLM, so we accept this tradeoff to
        // keep memory usage bounded on huge files.
        int[] headStarts = new int[topShown];
        int[] headEnds = new int[topShown];
        int headCount = 0;

        int[] tailStarts = new int[bottomShown];
        int[] tailEnds = new int[bottomShown];
        int tailSize = 0;
        int tailPos = 0;

        int totalLines = 0;
        int lineStart = 0;

        int i = 0;
        while (i < length) {
            char c = content.charAt(i);
            if (c == '\n' || c == '\r') {
                int lineEnd = i;

                totalLines++;
                if (headCount < topShown) {
                    headStarts[headCount] = lineStart;
                    headEnds[headCount] = lineEnd;
                    headCount++;
                }
                if (bottomShown > 0) {
                    if (tailSize < bottomShown) {
                        tailStarts[tailSize] = lineStart;
                        tailEnds[tailSize] = lineEnd;
                        tailSize++;
                    } else {
                        tailStarts[tailPos] = lineStart;
                        tailEnds[tailPos] = lineEnd;
                        tailPos = (tailPos + 1) % bottomShown;
                    }
                }

                // Treat CRLF as a single line break.
                if (c == '\r' && (i + 1) < length && content.charAt(i + 1) == '\n') {
                    i++;
                }
                i++;
                lineStart = i;
                continue;
            }
            i++;
        }

        // Flush the final line (including a trailing empty line if the content ends with a line break).
        totalLines++;
        if (headCount < topShown) {
            headStarts[headCount] = lineStart;
            headEnds[headCount] = length;
            headCount++;
        }
        if (bottomShown > 0) {
            if (tailSize < bottomShown) {
                tailStarts[tailSize] = lineStart;
                tailEnds[tailSize] = length;
                tailSize++;
            } else {
                tailStarts[tailPos] = lineStart;
                tailEnds[tailPos] = length;
                tailPos = (tailPos + 1) % bottomShown;
            }
        }

        if (totalLines <= maxLines) {
            return HeadTail.full(capAllLines(content, maxCharsPerLine));
        }

        int top = min(topShown, totalLines);
        int bottom = min(bottomShown, max(0, totalLines - top));
        int omitted = max(0, totalLines - top - bottom);

        String head = joinLines(content, headStarts, headEnds, 0, top, maxCharsPerLine);

        String tail = "";
        if (bottom > 0) {
            int orderedSize = tailSize;
            int orderedStart = (tailSize < bottomShown) ? 0 : tailPos;
            int skip = max(0, orderedSize - bottom);

            var tailLines = new ArrayList<String>(bottom);
            for (int n = skip; n < orderedSize; n++) {
                int ringIndex = (orderedStart + n) % orderedSize;
                tailLines.add(truncateLine(content, tailStarts[ringIndex], tailEnds[ringIndex], maxCharsPerLine));
            }
            tail = String.join("\n", tailLines);
        }

        String delimiter = "----- BRK_OMITTED " + omitted + " LINES -----";

        var parts = new ArrayList<String>(3);
        if (!head.isEmpty()) {
            parts.add(head);
        }
        parts.add(delimiter);
        if (!tail.isEmpty()) {
            parts.add(tail);
        }

        String promptText = String.join("\n\n", parts);
        return HeadTail.truncated(promptText, totalLines, topShown, bottomShown);
    }

    private static String joinLines(
            String content, int[] starts, int[] ends, int startIndex, int count, int maxCharsPerLine) {
        if (count <= 0) {
            return "";
        }
        if (count == 1) {
            return truncateLine(content, starts[startIndex], ends[startIndex], maxCharsPerLine);
        }
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int idx = startIndex + i;
            lines.add(truncateLine(content, starts[idx], ends[idx], maxCharsPerLine));
        }
        return String.join("\n", lines);
    }

    private static String truncateLine(String content, int startInclusive, int endExclusive, int maxCharsPerLine) {
        int len = endExclusive - startInclusive;
        if (len <= maxCharsPerLine) {
            return content.substring(startInclusive, endExclusive);
        }
        int omitted = len - maxCharsPerLine;
        return content.substring(startInclusive, startInclusive + maxCharsPerLine)
                + " [BRK_TRUNCATED "
                + omitted
                + " CHARS]";
    }

    private static String capAllLines(String content, int maxCharsPerLine) {
        int length = content.length();
        StringBuilder out = new StringBuilder(min(length, maxCharsPerLine * 2));

        int lineStart = 0;
        int i = 0;
        while (i < length) {
            char c = content.charAt(i);
            if (c == '\n' || c == '\r') {
                int lineEnd = i;
                out.append(truncateLine(content, lineStart, lineEnd, maxCharsPerLine));

                // Treat CRLF as a single line break.
                if (c == '\r' && (i + 1) < length && content.charAt(i + 1) == '\n') {
                    i++;
                }

                out.append('\n');
                i++;
                lineStart = i;
                continue;
            }
            i++;
        }

        out.append(truncateLine(content, lineStart, length, maxCharsPerLine));
        return out.toString();
    }

    public record HeadTail(String promptText, boolean truncated, int totalLines, int topShown, int bottomShown) {
        public static HeadTail full(String promptText) {
            return new HeadTail(promptText, false, 0, 0, 0);
        }

        static HeadTail truncated(String promptText, int totalLines, int topShown, int bottomShown) {
            return new HeadTail(promptText, true, totalLines, topShown, bottomShown);
        }
    }
}
