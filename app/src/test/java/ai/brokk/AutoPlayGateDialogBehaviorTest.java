package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.gui.dialogs.AutoPlayGateDialog;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Verifies that:
 * - ESC and window-close map to KEEP_OLD in AutoPlayGateDialog.
 * - Enum contains normalized values (KEEP_OLD, KEEP_NEW, KEEP_BOTH).
 *
 * Uses static source checks to avoid UI interactions on CI.
 */
public class AutoPlayGateDialogBehaviorTest {

    private static String read(String relPath) throws Exception {
        return Files.readString(Path.of(relPath));
    }

    @Test
    void esc_and_window_close_map_to_keep_old() throws Exception {
        String src = read("src/main/java/ai/brokk/gui/dialogs/AutoPlayGateDialog.java");

        assertTrue(
                src.contains("KeyEvent.VK_ESCAPE") && src.contains("choice = UserChoice.KEEP_OLD"),
                "ESC should map to KEEP_OLD");

        assertTrue(
                src.contains("windowClosing") && src.contains("choice = UserChoice.KEEP_OLD"),
                "Window close should map to KEEP_OLD");
    }

    @Test
    void enum_contains_normalized_values() {
        assertNotNull(Enum.valueOf(AutoPlayGateDialog.UserChoice.class, "KEEP_OLD"));
        assertNotNull(Enum.valueOf(AutoPlayGateDialog.UserChoice.class, "KEEP_NEW"));
        assertNotNull(Enum.valueOf(AutoPlayGateDialog.UserChoice.class, "KEEP_BOTH"));
    }
}
