package ai.brokk.gui.terminal;

import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import java.awt.Point;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class BrokkJediTermPanel extends TerminalPanel {

    private final TerminalTextBuffer terminalTextBuffer;

    public BrokkJediTermPanel(
            @NotNull SettingsProvider settingsProvider,
            @NotNull TerminalTextBuffer terminalTextBuffer,
            @NotNull StyleState styleState) {
        super(settingsProvider, terminalTextBuffer, styleState);
        this.terminalTextBuffer = terminalTextBuffer;
    }

    public void updateFontAndResize() {
        reinitFontAndResize();
    }

    @NotNull
    public String getFullBufferText() {
        return computeFullBufferText(new BufferAccessor() {
            @Override
            public void lock() {
                terminalTextBuffer.lock();
            }

            @Override
            public void unlock() {
                terminalTextBuffer.unlock();
            }

            @Override
            public int getHistoryLinesCount() {
                return terminalTextBuffer.getHistoryLinesCount();
            }

            @Override
            public int getHeight() {
                return terminalTextBuffer.getHeight();
            }

            @Override
            public String getLineText(int y) {
                return terminalTextBuffer.getLine(y).getText();
            }

            @Override
            public boolean isLineWrapped(int y) {
                return terminalTextBuffer.getLine(y).isWrapped();
            }
        });
    }

    @Nullable
    public String getSelectionText() {
        var selection = getSelection();
        if (selection == null) {
            return null;
        }
        var s = selection.getStart();
        var e = selection.getEnd();
        return getSelectionText(new Point(s.x, s.y), new Point(e.x, e.y));
    }

    @Nullable
    public String getSelectionText(@Nullable Point start, @Nullable Point end) {
        return computeSelectionText(
                new BufferAccessor() {
                    @Override
                    public void lock() {
                        terminalTextBuffer.lock();
                    }

                    @Override
                    public void unlock() {
                        terminalTextBuffer.unlock();
                    }

                    @Override
                    public int getHistoryLinesCount() {
                        return terminalTextBuffer.getHistoryLinesCount();
                    }

                    @Override
                    public int getHeight() {
                        return terminalTextBuffer.getHeight();
                    }

                    @Override
                    public String getLineText(int y) {
                        return terminalTextBuffer.getLine(y).getText();
                    }

                    @Override
                    public boolean isLineWrapped(int y) {
                        return terminalTextBuffer.getLine(y).isWrapped();
                    }
                },
                start,
                end);
    }

    interface BufferAccessor {
        void lock();

        void unlock();

        int getHistoryLinesCount();

        int getHeight();

        String getLineText(int y);

        boolean isLineWrapped(int y);
    }

    static String computeFullBufferText(BufferAccessor buffer) {
        var lines = new java.util.ArrayList<String>();
        buffer.lock();
        try {
            int historyCount = buffer.getHistoryLinesCount();
            int height = buffer.getHeight();

            for (int i = 0; i < historyCount; i++) {
                lines.add(buffer.getLineText(i - historyCount));
            }

            for (int i = 0; i < height; i++) {
                lines.add(buffer.getLineText(i));
            }
        } finally {
            buffer.unlock();
        }

        var sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i).replaceAll("\\s+$", ""));
            if (i < lines.size() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    static @Nullable String computeSelectionText(BufferAccessor buffer, @Nullable Point start, @Nullable Point end) {
        if (start == null || end == null) {
            return null;
        }

        var p1 = start;
        var p2 = end;

        // Normalize coordinates
        if (p1.y > p2.y || (p1.y == p2.y && p1.x > p2.x)) {
            var tmp = p1;
            p1 = p2;
            p2 = tmp;
        }

        var sb = new StringBuilder();
        buffer.lock();
        try {
            int historyCount = buffer.getHistoryLinesCount();
            int height = buffer.getHeight();
            int minIndex = -historyCount;
            int maxIndex = height - 1;

            int yStart = Math.max(minIndex, Math.min(p1.y, maxIndex));
            int yEnd = Math.max(minIndex, Math.min(p2.y, maxIndex));

            for (int y = yStart; y <= yEnd; y++) {
                String text = buffer.getLineText(y);
                int maxX = text.length();

                int x1 = (y == p1.y) ? p1.x : 0;
                // Treat end coordinate as inclusive to capture the last character
                int x2 = (y == p2.y) ? p2.x + 1 : maxX;

                x1 = Math.min(Math.max(x1, 0), maxX);
                x2 = Math.min(Math.max(x2, 0), maxX);

                if (x1 < x2) {
                    String chunk = text.substring(x1, x2);
                    sb.append(chunk);
                }

                if (y < p2.y && !buffer.isLineWrapped(y)) {
                    sb.append('\n');
                }
            }
        } finally {
            buffer.unlock();
        }

        return sb.toString();
    }
}
