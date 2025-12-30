package ai.brokk.gui.terminal;

import com.jediterm.terminal.model.StyleState;
import java.awt.Point;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;
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
        terminalTextBuffer.lock();
        try {
            for (int y = p1.y; y <= p2.y; y++) {
                var line = terminalTextBuffer.getLine(y);
                String text = line.getText();
                int maxX = text.length();

                int x1 = (y == p1.y) ? p1.x : 0;
                // Treat end coordinate as inclusive to capture the last character
                int x2 = (y == p2.y) ? p2.x + 1 : maxX;

                x1 = Math.min(Math.max(x1, 0), maxX);
                x2 = Math.min(Math.max(x2, 0), maxX);

                if (x1 < x2) {
                    sb.append(text, x1, x2);
                }

                if (y < p2.y && !line.isWrapped()) {
                    sb.append('\n');
                }
            }
        } finally {
            terminalTextBuffer.unlock();
        }

        return sb.toString();
    }
}
