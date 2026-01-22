package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class InstructionsPanelTest {

    @Test
    void testIsPlaceholderMatch_ignoresNewlineStyle() {
        assertTrue(InstructionsPanel.isPlaceholderMatch("\n", "\r\n"));
        assertTrue(InstructionsPanel.isPlaceholderMatch("\r\n", "\n"));
        assertTrue(InstructionsPanel.isPlaceholderMatch("Prompt\n", "Prompt\r\n"));

        assertFalse(InstructionsPanel.isPlaceholderMatch(null, "\n"));
        assertFalse(InstructionsPanel.isPlaceholderMatch("\n", null));
    }
}
