package io.github.jbellis.brokk.gui.terminal;

import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import org.jetbrains.annotations.NotNull;

public class BrokkJediTermPanel extends TerminalPanel {

    public BrokkJediTermPanel(
            @NotNull SettingsProvider settingsProvider,
            @NotNull TerminalTextBuffer terminalTextBuffer,
            @NotNull StyleState styleState) {
        super(settingsProvider, terminalTextBuffer, styleState);
    }

    public void updateFontAndResize() {
        reinitFontAndResize();
    }
}
