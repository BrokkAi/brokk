package ai.brokk.gui.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jediterm.terminal.TerminalColor;
import java.awt.Color;
import org.junit.jupiter.api.Test;

class MutableSettingsProviderTest {

    @Test
    @SuppressWarnings("deprecation")
    void getDefaultStyle_ReturnsCorrectColors() {
        Color bg = Color.BLACK;
        Color fg = Color.WHITE;
        Color selBg = Color.BLUE;
        Color selFg = Color.YELLOW;

        MutableSettingsProvider provider = new MutableSettingsProvider(bg, fg, selBg, selFg);
        var style = provider.getDefaultStyle();

        assertEquals(toTerminalColor(fg), style.getForeground(), "Style foreground should match");
        assertEquals(toTerminalColor(bg), style.getBackground(), "Style background should match");
        assertEquals(toTerminalColor(bg), provider.getDefaultBackground(), "Default background should match");
        assertEquals(toTerminalColor(fg), provider.getDefaultForeground(), "Default foreground should match");
    }

    @Test
    void colorsUpdateCorrectly() {
        MutableSettingsProvider provider =
                new MutableSettingsProvider(Color.BLACK, Color.WHITE, Color.BLUE, Color.YELLOW);

        TerminalColor newBg = new TerminalColor(50, 50, 50);
        TerminalColor newFg = new TerminalColor(255, 0, 0);

        provider.setBackground(newBg);
        provider.setForeground(newFg);

        assertEquals(newFg, provider.getDefaultStyle().getForeground());
        assertEquals(newBg, provider.getDefaultStyle().getBackground());
        assertEquals(newBg, provider.getDefaultBackground());
        assertEquals(newFg, provider.getDefaultForeground());
    }

    private static TerminalColor toTerminalColor(Color c) {
        return new TerminalColor(c.getRed(), c.getGreen(), c.getBlue());
    }
}
