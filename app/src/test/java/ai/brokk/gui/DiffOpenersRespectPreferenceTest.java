package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;

import ai.brokk.ContextManager;
import ai.brokk.difftool.ui.BrokkDiffPanel;
import ai.brokk.difftool.ui.BufferSource;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.testutil.TestProject;
import ai.brokk.util.GlobalUiSettings;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lightweight regression test to ensure external callers DO NOT override the global diff
 * view preference when opening diffs. Callers must not write GlobalUiSettings on behalf
 * of the user; BrokkDiffPanel persists the preference when the user toggles the view.
 *
 * Fixes #1679
 */
public class DiffOpenersRespectPreferenceTest {

    @AfterEach
    void cleanup() {
        GlobalUiSettings.resetForTests();
        System.clearProperty("brokk.ui.config.dir");
    }

    @Test
    void buildingPanelDoesNotChangeGlobalPreference(@TempDir Path tempDir) {
        // Skip on headless environments because GuiTheme/Chrome require a frame
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires non-headless AWT");

        // Isolate global UI settings to a temp directory
        System.setProperty("brokk.ui.config.dir", tempDir.toString());
        GlobalUiSettings.resetForTests();

        // Start with side-by-side (unified=false)
        GlobalUiSettings.saveDiffUnifiedView(false);
        assertFalse(GlobalUiSettings.isDiffUnifiedView(), "Precondition: unified should be false");

        // Minimal harness similar to GitCommitTab/HistoryOutputPanel flows:
        var project = new TestProject(tempDir);
        var contextManager = new ContextManager(project);

        // Build theme without showing a window
        var frame = Chrome.newFrame("DiffOpenersRespectPreferenceTest");
        var theme = new GuiTheme(frame, null, new Chrome(contextManager));

        // Prepare a simple comparison (no Git metadata, no file IO required)
        var left = new BufferSource.StringSource("left content", "Left", "file.txt");
        var right = new BufferSource.StringSource("right content", "Right", "file.txt");

        // Build a BrokkDiffPanel via its Builder (what external callers do)
        var builder = new BrokkDiffPanel.Builder(theme, contextManager);
        builder.addComparison(left, right);
        var panel = builder.build();

        // Creating/opening a panel must NOT change the persisted preference.
        assertFalse(
                GlobalUiSettings.isDiffUnifiedView(),
                "Builder/build must not override GlobalUiSettings; only user toggle in BrokkDiffPanel may persist");

        // Cleanup UI resources
        try {
            panel.dispose();
        } catch (Throwable ignored) {
        }
        try {
            frame.dispose();
        } catch (Throwable ignored) {
        }
    }
}
