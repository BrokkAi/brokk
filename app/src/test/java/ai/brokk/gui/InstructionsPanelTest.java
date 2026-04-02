package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class InstructionsPanelTest {

    @Test
    void testIsPlaceholderMatch_ignoresNewlineStyleOnly() {
        // Newline style differences are ignored
        assertTrue(InstructionsPanel.isPlaceholderMatch("\n", "\r\n"));
        assertTrue(InstructionsPanel.isPlaceholderMatch("\r\n", "\n"));
        assertTrue(InstructionsPanel.isPlaceholderMatch("Prompt\n", "Prompt\r\n"));
        assertTrue(InstructionsPanel.isPlaceholderMatch("Line1\nLine2", "Line1\r\nLine2"));
        assertTrue(InstructionsPanel.isPlaceholderMatch("Line1\r\nLine2\r\n", "Line1\nLine2\n"));

        // Non-newline whitespace differences are NOT ignored
        assertFalse(InstructionsPanel.isPlaceholderMatch("Prompt", "Prompt \n"));
        assertFalse(InstructionsPanel.isPlaceholderMatch("Prompt", "Prompt "));
        assertFalse(InstructionsPanel.isPlaceholderMatch(" Prompt", "Prompt"));
        assertFalse(InstructionsPanel.isPlaceholderMatch("Prompt\n", "Prompt"));
        assertFalse(InstructionsPanel.isPlaceholderMatch("Prompt \r\n", "Prompt\r\n"));

        // Null handling
        assertFalse(InstructionsPanel.isPlaceholderMatch(null, "\n"));
        assertFalse(InstructionsPanel.isPlaceholderMatch("\n", null));
        assertFalse(InstructionsPanel.isPlaceholderMatch(null, null));
    }
}
