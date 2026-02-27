package ai.brokk.util;

import static java.lang.Math.max;
import static java.lang.Math.min;

import java.util.ArrayList;
import java.util.List;

public final class Lines {
    public static final int MAX_CHARS_PER_LINE = 2048; // Claude Code is 2000

    private Lines() {}

    public static HeadTail cap(String content, int maxLines, int topShown, int bottomShown) {
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
            return HeadTail.full(capAllLines(content));
        }

        int top = min(topShown, totalLines);
        int bottom = min(bottomShown, max(0, totalLines - top));
        int omitted = max(0, totalLines - top - bottom);

        String head = joinLines(content, headStarts, headEnds, 0, top);

        String tail = "";
        if (bottom > 0) {
            int orderedSize = tailSize;
            int orderedStart = (tailSize < bottomShown) ? 0 : tailPos;
            int skip = max(0, orderedSize - bottom);

            var tailLines = new ArrayList<String>(bottom);
            for (int n = skip; n < orderedSize; n++) {
                int ringIndex = (orderedStart + n) % orderedSize;
                tailLines.add(truncateLine(content, tailStarts[ringIndex], tailEnds[ringIndex]));
            }
            tail = String.join("\n", tailLines);
        }

        String delimiter = "----- OMITTED " + omitted + " LINES -----";

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

    public record RangeResult(String text, int lineCount) {}

    public static RangeResult range(String content, int oneBasedStartInclusive, int oneBasedEndInclusive) {
        if (oneBasedStartInclusive < 1) {
            throw new IllegalArgumentException("oneBasedStartInclusive must be >= 1");
        }
        if (oneBasedEndInclusive < oneBasedStartInclusive) {
            throw new IllegalArgumentException("oneBasedEndInclusive must be >= oneBasedStartInclusive");
        }
        if (content.isEmpty()) {
            return new RangeResult("", 0);
        }

        int length = content.length();
        int currentLine = 1;
        int lineStart = 0;
        List<String> outputLines = new ArrayList<>();

        int i = 0;
        while (i < length && currentLine <= oneBasedEndInclusive) {
            char c = content.charAt(i);
            if (c == '\n' || c == '\r') {
                int lineEnd = i;
                int next = i + 1;
                if (c == '\r' && next < length && content.charAt(next) == '\n') {
                    next++;
                }

                if (currentLine >= oneBasedStartInclusive) {
                    String line = truncateLine(content, lineStart, lineEnd);
                    outputLines.add(currentLine + ": " + line);
                }

                currentLine++;
                lineStart = next;
                i = next;
            } else {
                i++;
            }
        }

        // Handle last line if it doesn't end with a newline
        if (lineStart < length && currentLine >= oneBasedStartInclusive && currentLine <= oneBasedEndInclusive) {
            String line = truncateLine(content, lineStart, length);
            outputLines.add(currentLine + ": " + line);
        }

        if (outputLines.isEmpty()) {
            return new RangeResult("", 0);
        }

        return new RangeResult(String.join("\n", outputLines) + "\n", outputLines.size());
    }

    private static String joinLines(String content, int[] starts, int[] ends, int startIndex, int count) {
        if (count <= 0) {
            return "";
        }
        if (count == 1) {
            return truncateLine(content, starts[startIndex], ends[startIndex]);
        }
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int idx = startIndex + i;
            lines.add(truncateLine(content, starts[idx], ends[idx]));
        }
        return String.join("\n", lines);
    }

    public static String truncateLine(String content, int startInclusive, int endExclusive) {
        int len = endExclusive - startInclusive;
        if (len <= MAX_CHARS_PER_LINE) {
            return content.substring(startInclusive, endExclusive);
        }
        return content.substring(startInclusive, startInclusive + MAX_CHARS_PER_LINE) + " [TRUNCATED at "
                + MAX_CHARS_PER_LINE + " chars]";
    }

    private static String capAllLines(String content) {
        int length = content.length();
        StringBuilder out = new StringBuilder(min(length, MAX_CHARS_PER_LINE * 2));

        int lineStart = 0;
        int i = 0;
        while (i < length) {
            char c = content.charAt(i);
            if (c == '\n' || c == '\r') {
                int lineEnd = i;
                out.append(truncateLine(content, lineStart, lineEnd));

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

        out.append(truncateLine(content, lineStart, length));
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
