package ai.brokk.gui.dialogs;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import ai.brokk.gui.util.KeyboardShortcutUtil;
import ai.brokk.util.GlobalUiSettings;
import com.formdev.flatlaf.util.SystemInfo;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.Test;

/** Persistence tests for global undo/redo keybindings on non-macOS platforms. */
public class SettingsGlobalPanelKeybindingPersistenceTest {

    @Test
    void testUndoKeybindingPersistence_NonMac() {
        assumeFalse(SystemInfo.isMacOS, "These persistence tests are only relevant on non-macOS platforms");

        KeyStroke undoDefault = SettingsGlobalPanel.defaultUndo();
        assertNotNull(undoDefault);
        assertEquals(KeyEvent.VK_Z, undoDefault.getKeyCode());

        GlobalUiSettings.saveKeybinding("test.undo", undoDefault);
        KeyStroke roundTripped = GlobalUiSettings.getKeybinding("test.undo", null);

        assertNotNull(roundTripped);
        assertEquals(KeyEvent.VK_Z, roundTripped.getKeyCode());
        assertTrue(
                (roundTripped.getModifiers() & InputEvent.CTRL_DOWN_MASK) != 0,
                "Undo should use Ctrl modifier on non-macOS");
        assertEquals(
                0, roundTripped.getModifiers() & InputEvent.SHIFT_DOWN_MASK, "Undo should not include Shift modifier");
    }

    @Test
    void testRedoKeybindingPersistence_NonMac() {
        assumeFalse(SystemInfo.isMacOS, "These persistence tests are only relevant on non-macOS platforms");

        KeyStroke redoDefault = SettingsGlobalPanel.defaultRedo();
        assertNotNull(redoDefault);
        assertEquals(KeyEvent.VK_Z, redoDefault.getKeyCode());

        GlobalUiSettings.saveKeybinding("test.redo", redoDefault);
        KeyStroke roundTripped = GlobalUiSettings.getKeybinding("test.redo", null);

        assertNotNull(roundTripped);
        assertEquals(KeyEvent.VK_Z, roundTripped.getKeyCode());
        assertTrue(
                (roundTripped.getModifiers() & InputEvent.CTRL_DOWN_MASK) != 0,
                "Redo should always include Ctrl on non-macOS");
        assertTrue(
                (roundTripped.getModifiers() & InputEvent.SHIFT_DOWN_MASK) != 0,
                "Redo (Z) should include Shift modifier");
    }

    @Test
    void testRedoYKeybindingPersistence_NonMac() {
        assumeFalse(SystemInfo.isMacOS, "These persistence tests are only relevant on non-macOS platforms");

        KeyStroke redoYDefault = KeyboardShortcutUtil.createCtrlY();
        assertNotNull(redoYDefault);
        assertEquals(KeyEvent.VK_Y, redoYDefault.getKeyCode());

        GlobalUiSettings.saveKeybinding("test.redoY", redoYDefault);
        KeyStroke roundTripped = GlobalUiSettings.getKeybinding("test.redoY", null);

        assertNotNull(roundTripped);
        assertEquals(KeyEvent.VK_Y, roundTripped.getKeyCode());
        assertTrue(
                (roundTripped.getModifiers() & InputEvent.CTRL_DOWN_MASK) != 0,
                "Redo (Y) should use Ctrl modifier on non-macOS");
        assertEquals(
                0,
                roundTripped.getModifiers() & InputEvent.SHIFT_DOWN_MASK,
                "Redo (Y) should not include Shift modifier");
    }
}
