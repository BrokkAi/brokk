package ai.brokk.gui.terminal;

import com.jediterm.terminal.model.StyleState;
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

        var start = selection.getStart();
        var end = selection.getEnd();

        if (start == null || end == null) {
            return null;
        }

        if (start.y > end.y || (start.y == end.y && start.x > end.x)) {
            var tmp = start;
            start = end;
            end = tmp;
        }

        var sb = new StringBuilder();
        terminalTextBuffer.lock();
        try {
            for (int y = start.y; y <= end.y; y++) {
                var line = terminalTextBuffer.getLine(y);
                String text = line.getText();
                int maxX = text.length();

                int x1 = (y == start.y) ? start.x : 0;
                // Treat end coordinate as inclusive to capture the last character
                int x2 = (y == end.y) ? end.x + 1 : maxX;

                x1 = Math.min(Math.max(x1, 0), maxX);
                x2 = Math.min(Math.max(x2, 0), maxX);

                if (x1 < x2) {
                    sb.append(text, x1, x2);
                }

                if (y < end.y && !line.isWrapped()) {
                    sb.append('\n');
                }
            }
        } finally {
            terminalTextBuffer.unlock();
        }

        return sb.toString();
    }
}
