package ai.brokk.cli.tui;

import java.util.ArrayList;
import java.util.List;

final class TuiBuffer {
    private final int maxLines;
    private final List<String> completedLines = new ArrayList<>();
    private String currentLine = "";

    TuiBuffer(int maxLines) {
        if (maxLines < 1) {
            throw new IllegalArgumentException("maxLines must be positive");
        }
        this.maxLines = maxLines;
    }

    synchronized void clear() {
        completedLines.clear();
        currentLine = "";
    }

    synchronized void append(String text) {
        if (text.isEmpty()) {
            return;
        }
        String[] parts = text.replace("\t", "    ").split("\n", -1);
        if (parts.length == 0) {
            return;
        }
        currentLine = currentLine + parts[0];
        for (int i = 1; i < parts.length; i++) {
            completedLines.add(currentLine);
            trimIfNeeded();
            currentLine = parts[i];
        }
    }

    synchronized List<String> snapshotWrapped(int width, boolean wrapEnabled) {
        int effectiveWidth = wrapEnabled ? Math.max(width, 1) : Integer.MAX_VALUE;
        List<String> raw = new ArrayList<>(completedLines.size() + 1);
        raw.addAll(completedLines);
        raw.add(currentLine);

        List<String> wrapped = new ArrayList<>();
        for (String line : raw) {
            wrapped.addAll(wrapLine(line, effectiveWidth));
        }
        return wrapped;
    }

    synchronized int totalWrappedLines(int width, boolean wrapEnabled) {
        return snapshotWrapped(width, wrapEnabled).size();
    }

    private void trimIfNeeded() {
        // Keep the total (completed + current) bounded. In steady-state we render the current line too.
        while (completedLines.size() > maxLines - 1) {
            completedLines.remove(0);
        }
    }

    private static List<String> wrapLine(String line, int width) {
        if (width <= 0) {
            return List.of("");
        }
        if (line.isEmpty()) {
            return List.of("");
        }
        if (line.length() <= width) {
            return List.of(line);
        }
        List<String> out = new ArrayList<>();
        int index = 0;
        while (index < line.length()) {
            int end = Math.min(index + width, line.length());
            out.add(line.substring(index, end));
            index = end;
        }
        return out;
    }
}
