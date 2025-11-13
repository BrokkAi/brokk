package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

public class ChromeRedoKeybindingsTest {

    @Test
    public void testRegisterRedoKeybindingsRegistersBothStrokes() throws Exception {
        // Run UI actions on EDT and perform assertions
        SwingUtilities.invokeAndWait(() -> {
            JRootPane root = new JRootPane();
            Action noop = new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    // no-op
                }
            };

            // Invoke the helper under test (should be headless-safe)
            Chrome.registerRedoKeybindings(root, noop);

            InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            KeyStroke[] keys = im.allKeys();
            assertNotNull(keys, "InputMap should not be empty");

            boolean foundZ = false;
            boolean foundY = false;

            for (KeyStroke ks : keys) {
                if (ks == null) continue;
                Object val = im.get(ks);
                if (!"globalRedo".equals(val)) continue;

                int kc = ks.getKeyCode();
                int mods = ks.getModifiers();

                // Expect one mapping for platform-menu + SHIFT + Z (redo)
                if (kc == KeyEvent.VK_Z) {
                    boolean hasShift = (mods & InputEvent.SHIFT_DOWN_MASK) != 0;
                    boolean hasMenu = (mods & (InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK)) != 0;
                    if (hasShift && hasMenu) {
                        foundZ = true;
                    }
                }

                // Expect one mapping for platform-menu + Y (redo on some platforms)
                if (kc == KeyEvent.VK_Y) {
                    boolean hasMenu = (mods & (InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK)) != 0;
                    boolean noShift = (mods & InputEvent.SHIFT_DOWN_MASK) == 0;
                    if (hasMenu && noShift) {
                        foundY = true;
                    }
                }
            }

            assertTrue(foundZ, "Expected a KeyStroke for Cmd/Ctrl+Shift+Z mapping to 'globalRedo'");
            assertTrue(foundY, "Expected a KeyStroke for Cmd/Ctrl+Y mapping to 'globalRedo'");

            Object mappedAction = root.getActionMap().get("globalRedo");
            assertSame(noop, mappedAction, "ActionMap should contain the provided Action under 'globalRedo'");
        });
    }
}
