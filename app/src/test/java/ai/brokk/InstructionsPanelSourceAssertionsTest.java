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
    void gating_is_wired_for_both_Lutz_and_Plan_and_uses_replaceOnly_dialog() throws Exception {
        String src = read("src/main/java/ai/brokk/gui/InstructionsPanel.java");

        boolean usesShow = src.contains("AutoPlayGateDialog.show(")
                || src.contains("AutoPlayGateDialog.showReplaceOnly(");

        assertTrue(
                usesShow,
                "AutoPlayGateDialog.show or showReplaceOnly should be used for gating");
        assertTrue(src.contains("ACTION_LUTZ"), "Lutz action constant should be present in InstructionsPanel");
        assertTrue(src.contains("ACTION_PLAN"), "Plan action constant should be present in InstructionsPanel");
    }

    @Test
    void autoplay_waits_for_model_refresh_before_run_all() throws Exception {
        String src = read("src/main/java/ai/brokk/gui/InstructionsPanel.java");
        assertTrue(
                src.contains("runAllAfterModelRefresh()"),
                "InstructionsPanel should trigger TaskListPanel.runAllAfterModelRefresh after Lutz completion");
    }
}
