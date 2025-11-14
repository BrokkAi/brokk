package ai.brokk.gui.util;

import static org.junit.jupiter.api.Assertions.*;

import com.formdev.flatlaf.util.SystemInfo;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.Test;

/** Tests for KeyboardShortcutUtil methods. All tests are headless-safe. */
public class KeyboardShortcutUtilTest {

    @Test
    void testCreateAltShortcut_CurrentOS() {
        // Test on the actual OS without mocking
        KeyStroke ks = KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_1);
        assertNotNull(ks);
        assertEquals(KeyEvent.VK_1, ks.getKeyCode());

        if (SystemInfo.isMacOS) {
            // On Mac, should use META (Cmd) not ALT
            assertTrue((ks.getModifiers() & KeyEvent.META_DOWN_MASK) != 0);
        } else {
            // On Windows/Linux, should use ALT not META
            assertTrue((ks.getModifiers() & KeyEvent.ALT_DOWN_MASK) != 0);
        }
    }

    @Test
    void testCreateAltShiftShortcut_CurrentOS() {
        KeyStroke ks = KeyboardShortcutUtil.createAltShiftShortcut(KeyEvent.VK_3);
        assertNotNull(ks);
        assertEquals(KeyEvent.VK_3, ks.getKeyCode());

        // Should always have SHIFT
        assertTrue((ks.getModifiers() & InputEvent.SHIFT_DOWN_MASK) != 0);

        if (SystemInfo.isMacOS) {
            // On Mac, should have META + SHIFT
            assertTrue((ks.getModifiers() & KeyEvent.META_DOWN_MASK) != 0);
        } else {
            // On Windows/Linux, should have ALT + SHIFT
            assertTrue((ks.getModifiers() & KeyEvent.ALT_DOWN_MASK) != 0);
        }
    }

    @Test
    void testCreateSimpleShortcut() {
        KeyStroke ks = KeyboardShortcutUtil.createSimpleShortcut(KeyEvent.VK_ESCAPE);
        assertNotNull(ks);
        assertEquals(KeyEvent.VK_ESCAPE, ks.getKeyCode());
        assertEquals(0, ks.getModifiers());
    }

    @Test
    void testFormatKeyStroke_NoModifiers() {
        KeyStroke ks = KeyboardShortcutUtil.createSimpleShortcut(KeyEvent.VK_ESCAPE);
        String formatted = KeyboardShortcutUtil.formatKeyStroke(ks);
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
    }

    @Test
    void testFormatKeyStroke_WithModifiers_Alt() {
        KeyStroke ks = KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_1);
        String formatted = KeyboardShortcutUtil.formatKeyStroke(ks);
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
        // Should contain '+' separator
        assertTrue(formatted.contains("+"), "Should contain '+' separator, got: " + formatted);
        // Should have at least 2 parts (modifier + key)
        String[] parts = formatted.split("\\+");
        assertTrue(parts.length >= 2, "Should have at least 2 parts (modifier+key), got: " + formatted);
        // Should contain key '1' in one of the parts
        assertTrue(formatted.contains("1"), "Should contain key '1', got: " + formatted);
    }

    @Test
    void testFormatKeyStroke_MultipleModifiers() {
        KeyStroke ks = KeyboardShortcutUtil.createAltShiftShortcut(KeyEvent.VK_3);
        String formatted = KeyboardShortcutUtil.formatKeyStroke(ks);
        assertNotNull(formatted);
        assertTrue(formatted.contains("+"), "Should contain '+' separator, got: " + formatted);
        // Should have at least 3 parts (modifier1 + modifier2 + key)
        String[] parts = formatted.split("\\+");
        assertTrue(parts.length >= 3, "Should have at least 3 parts (modifier1+modifier2+key), got: " + formatted);
        // Should contain key '3' in one of the parts
        assertTrue(formatted.contains("3"), "Should contain key '3', got: " + formatted);
    }

    @Test
    void testFormatKeyStroke_Stability() {
        // Verify that formatting the same KeyStroke multiple times gives consistent results
        KeyStroke ks = KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_5);
        String formatted1 = KeyboardShortcutUtil.formatKeyStroke(ks);
        String formatted2 = KeyboardShortcutUtil.formatKeyStroke(ks);
        assertEquals(formatted1, formatted2, "Formatting should be stable and consistent");
    }

    @Test
    void testFormatKeyStroke_NullKeyStroke() {
        // formatKeyStroke should handle null gracefully
        assertThrows(NullPointerException.class, () -> {
            KeyboardShortcutUtil.formatKeyStroke(null);
        });
    }

    @Test
    void testFormatKeyStroke_ExceptionFallback() {
        // Test that formatKeyStroke falls back to toString() on exception
        // Create a KeyStroke with unusual parameters that might trigger edge cases
        KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_UNDEFINED, 0);
        String formatted = KeyboardShortcutUtil.formatKeyStroke(ks);
        assertNotNull(formatted);
        // Should not throw exception, should return some string representation
        assertFalse(formatted.isEmpty());
    }

    @Test
    void testAllNumberKeysAltShortcuts() {
        // Test that Alt shortcuts work for all number keys 1-9
        for (int i = 1; i <= 9; i++) {
            int vkCode = KeyEvent.VK_0 + i;
            KeyStroke ks = KeyboardShortcutUtil.createAltShortcut(vkCode);
            assertNotNull(ks);
            assertEquals(vkCode, ks.getKeyCode());

            if (SystemInfo.isMacOS) {
                assertTrue((ks.getModifiers() & KeyEvent.META_DOWN_MASK) != 0);
            } else {
                assertTrue((ks.getModifiers() & KeyEvent.ALT_DOWN_MASK) != 0);
            }

            String formatted = KeyboardShortcutUtil.formatKeyStroke(ks);
            assertTrue(formatted.contains("+"), "Should contain '+' separator, got: " + formatted);
            String[] parts = formatted.split("\\+");
            assertTrue(parts.length >= 2, "Should have at least 2 parts (modifier+key), got: " + formatted);
            assertTrue(formatted.contains(String.valueOf(i)), "Should contain number " + i + ", got: " + formatted);
        }
    }

    @Test
    void testFormatKeyStroke_FunctionKeys() {
        // Test formatting of function keys
        KeyStroke f1 = KeyboardShortcutUtil.createSimpleShortcut(KeyEvent.VK_F1);
        String formatted = KeyboardShortcutUtil.formatKeyStroke(f1);
        assertTrue(formatted.contains("F1"));

        KeyStroke f12 = KeyboardShortcutUtil.createSimpleShortcut(KeyEvent.VK_F12);
        formatted = KeyboardShortcutUtil.formatKeyStroke(f12);
        assertTrue(formatted.contains("F12"));
    }

    @Test
    void testFormatKeyStroke_SpecialKeys() {
        KeyStroke enter = KeyboardShortcutUtil.createSimpleShortcut(KeyEvent.VK_ENTER);
        String formatted = KeyboardShortcutUtil.formatKeyStroke(enter);
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
        assertFalse(formatted.contains("+"), "Enter key without modifiers should not contain '+'");

        KeyStroke tab = KeyboardShortcutUtil.createSimpleShortcut(KeyEvent.VK_TAB);
        formatted = KeyboardShortcutUtil.formatKeyStroke(tab);
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
        assertFalse(formatted.contains("+"), "Tab key without modifiers should not contain '+'");

        KeyStroke space = KeyboardShortcutUtil.createSimpleShortcut(KeyEvent.VK_SPACE);
        formatted = KeyboardShortcutUtil.formatKeyStroke(space);
        assertNotNull(formatted);
        assertFalse(formatted.isEmpty());
        assertFalse(formatted.contains("+"), "Space key without modifiers should not contain '+'");
    }

    @Test
    void testModifierConsistency_Alt() {
        // Test that Alt shortcuts are consistent across multiple keys
        KeyStroke ks1 = KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_1);
        KeyStroke ks2 = KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_2);
        KeyStroke ks3 = KeyboardShortcutUtil.createAltShortcut(KeyEvent.VK_3);

        // All should have the same modifier bits
        assertEquals(ks1.getModifiers(), ks2.getModifiers());
        assertEquals(ks2.getModifiers(), ks3.getModifiers());
    }

    @Test
    void testModifierConsistency_AltShift() {
        // Test that Alt+Shift shortcuts are consistent across multiple keys
        KeyStroke ks1 = KeyboardShortcutUtil.createAltShiftShortcut(KeyEvent.VK_1);
        KeyStroke ks2 = KeyboardShortcutUtil.createAltShiftShortcut(KeyEvent.VK_2);

        // All should have the same modifier bits
        assertEquals(ks1.getModifiers(), ks2.getModifiers());
    }
}
