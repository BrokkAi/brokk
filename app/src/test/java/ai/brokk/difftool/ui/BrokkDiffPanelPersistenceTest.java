package ai.brokk.difftool.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.testutil.TestProject;
import ai.brokk.util.GlobalUiSettings;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.nio.file.Path;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test ensuring BrokkDiffPanel respects and persists the unified vs side-by-side
 * view preference using GlobalUiSettings.
 *
 * Fixes #1679
 */
public class BrokkDiffPanelPersistenceTest {

    @AfterEach
    void cleanup() {
        // Clear any cached properties so subsequent tests don't see this test's config
        GlobalUiSettings.resetForTests();
        System.clearProperty("brokk.ui.config.dir");
    }

    @Test
    void preferencePersistsAcrossPanels(@TempDir Path tempDir) {
        // Skip on headless environments since constructing GuiTheme requires a JFrame/Chrome
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires non-headless AWT");

        // Isolate settings to a temporary directory
        System.setProperty("brokk.ui.config.dir", tempDir.toString());
        GlobalUiSettings.resetForTests();

        // Start with side-by-side (unified=false) and verify the first panel initializes accordingly
        GlobalUiSettings.saveDiffUnifiedView(false);

        var project = new TestProject(tempDir);
        var contextManager = new ContextManager(project);

        // Minimal Chrome + GuiTheme for builder (no need to show any windows)
        var chrome = new Chrome(contextManager);
        var frame = Chrome.newFrame("TestDiffPanel");
        var theme = new GuiTheme(frame, null, chrome);

        // Minimal comparison
        var left = new BufferSource.StringSource("left content", "Left", "file.txt");
        var right = new BufferSource.StringSource("right content", "Right", "file.txt");

        var builder1 = new BrokkDiffPanel.Builder(theme, contextManager);
        builder1.addComparison(left, right);
        var panel1 = builder1.build();

        assertFalse(panel1.isUnifiedView(), "Panel should initialize to side-by-side when pref=false");

        // Toggle to unified via the panel's UI toggle (simulate user click on EDT)
        try {
            Field f = BrokkDiffPanel.class.getDeclaredField("viewModeToggle");
            f.setAccessible(true);
            JToggleButton toggle = (JToggleButton) f.get(panel1);
            SwingUtilities.invokeAndWait(toggle::doClick);
        } catch (Exception e) {
            throw new RuntimeException("Failed to toggle unified view via UI", e);
        }
        assertTrue(GlobalUiSettings.isDiffUnifiedView(), "Global setting should be unified=true after toggle");

        // Build a second panel and verify it initializes to unified
        var builder2 = new BrokkDiffPanel.Builder(theme, contextManager);
        builder2.addComparison(left, right);
        var panel2 = builder2.build();

        assertTrue(panel2.isUnifiedView(), "Panel should initialize to unified when pref=true");
    }
}
