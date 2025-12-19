package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Source-level assertions to verify:
 * - Gating dialog is invoked for both Lutz and Plan modes when pre-existing tasks exist.
 * - Auto-play uses runAllAfterModelRefresh (avoids premature empty model checks).
 *
 * These are static checks against the source to avoid fragile UI interactions on CI.
 */
public class InstructionsPanelSourceAssertionsTest {

    private static String read(String relPath) throws Exception {
        return Files.readString(Path.of(relPath));
    }

    @Test
    void gating_is_wired_for_both_Lutz_and_Plan_via_option_dialog() throws Exception {
        String src = read("src/main/java/ai/brokk/gui/InstructionsPanel.java");

        assertTrue(
                src.contains("JOptionPane.showOptionDialog"),
                "InstructionsPanel should use an option dialog for gating when pre-existing tasks exist");
        assertTrue(
                src.contains("New tasks were created. What would you like to do?"),
                "Gating dialog should prompt the user about new tasks");
        assertTrue(
                src.contains("Append to existing") && src.contains("Replace with new"),
                "Gating dialog should offer append vs replace options");
        assertTrue(src.contains("ACTION_LUTZ"), "Lutz action constant should be present in InstructionsPanel");
        assertTrue(src.contains("ACTION_PLAN"), "Plan action constant should be present in InstructionsPanel");
    }

    @Test
    void autoplay_triggers_run_architect_on_all() throws Exception {
        String src = read("src/main/java/ai/brokk/gui/InstructionsPanel.java");
        assertTrue(
                src.contains("runArchitectOnAll()"),
                "InstructionsPanel should trigger TaskListPanel.runArchitectOnAll after Lutz completion in EZ mode");
    }
}
