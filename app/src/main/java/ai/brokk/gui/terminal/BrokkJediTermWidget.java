package ai.brokk.gui.terminal;

import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalPanel; // Still need this import for the method signature
import com.jediterm.terminal.ui.settings.SettingsProvider;
import org.jetbrains.annotations.NotNull;

public class BrokkJediTermWidget extends JediTermWidget {

    public BrokkJediTermWidget(@NotNull SettingsProvider settingsProvider) {
        super(settingsProvider);
    }

    /** This is the crucial override. We tell the widget to instantiate our custom panel instead of the default one. */
    @Override
    protected TerminalPanel createTerminalPanel(
            @NotNull SettingsProvider settingsProvider,
            @NotNull StyleState styleState,
            @NotNull TerminalTextBuffer terminalTextBuffer) {
        return new BrokkJediTermPanel(settingsProvider, terminalTextBuffer, styleState);
    }

    public void updateFontAndResize() {
        BrokkJediTermPanel termPanel = (BrokkJediTermPanel) this.getTerminalDisplay();
        termPanel.updateFontAndResize();
    }
}
