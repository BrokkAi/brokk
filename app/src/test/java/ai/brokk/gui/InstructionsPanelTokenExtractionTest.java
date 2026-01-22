package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

public class InstructionsPanelTokenExtractionTest {

    @Test
    void emptyInputYieldsEmpty() {
        assertEquals("", InstructionsPanel.extractAlreadyEnteredText(""));
        assertEquals("", InstructionsPanel.extractAlreadyEnteredText("   "));
    }

    @Test
    void dotPrefixedIdentifierDropsDot() {
        assertEquals("Chrome", InstructionsPanel.extractAlreadyEnteredText(".Chrome"));
        assertEquals("Chr", InstructionsPanel.extractAlreadyEnteredText("See .Chr"));
    }

    @Test
    void relativePathsArePreserved() {
        assertEquals("./src", InstructionsPanel.extractAlreadyEnteredText("cd ./src"));
        assertEquals("../foo", InstructionsPanel.extractAlreadyEnteredText("cd ../foo"));
        assertEquals(".\\windows", InstructionsPanel.extractAlreadyEnteredText("copy .\\windows"));
    }

    @Test
    void packagePathWithDotIsKept() {
        assertEquals("ai.brokk.gui.C", InstructionsPanel.extractAlreadyEnteredText("ai.brokk.gui.C"));
        assertEquals("ai.brokk.gui.Chr", InstructionsPanel.extractAlreadyEnteredText("`ai.brokk.gui.Chr"));
    }

    @Test
    void leadingPunctuationBeforeWordIsIgnored() {
        assertEquals("Type", InstructionsPanel.extractAlreadyEnteredText("(Type"));
        assertEquals("Foo", InstructionsPanel.extractAlreadyEnteredText("`Foo"));
    }

    @Test
    void testIsPlaceholderMatch_ignoresNewlineStyle() {
        assertTrue(InstructionsPanel.isPlaceholderMatch("\n", "\r\n"));
        assertTrue(InstructionsPanel.isPlaceholderMatch("\r\n", "\n"));
        assertTrue(InstructionsPanel.isPlaceholderMatch("Prompt\n", "Prompt\r\n"));

        assertFalse(InstructionsPanel.isPlaceholderMatch(null, "\n"));
        assertFalse(InstructionsPanel.isPlaceholderMatch("\n", null));
    }
}
