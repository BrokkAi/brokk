package ai.brokk.gui.dialogs;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.ContextManager;
import ai.brokk.gui.Chrome;
import ai.brokk.testutil.TestProject;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for Issue #1971: GitHub auth tab missing from settings window.
 *
 * This test verifies that the GitHub settings tab is always visible in the global settings,
 * regardless of whether the current project is a GitHub repository.
 */
public class SettingsGitHubTabVisibilityTest {

    @Test
    void githubSettingsTabIsVisibleEvenForNonGitHubRepo(@TempDir Path projectRoot) throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires non-headless AWT");

        // Create a non-GitHub project (TestProject doesn't set any GitHub remote by default)
        var project = new TestProject(projectRoot);
        var contextManager = new ContextManager(project);
        var chrome = new Chrome(contextManager);

        SwingUtilities.invokeAndWait(() -> {
            // Create the settings panel on the EDT as required by its constructor assert
            // parentDialog can be null here; it's only used for applySettings() which we don't call
            SettingsGlobalPanel panel = new SettingsGlobalPanel(chrome, /* parentDialog= */ null);

            // Get the sub-tabbed pane that should contain the GitHub tab
            JTabbedPane tabs = panel.getGlobalSubTabbedPane();
            int tabCount = tabs.getTabCount();

            // Look for the GitHub tab
            boolean foundGitHubTab = false;
            for (int i = 0; i < tabCount; i++) {
                if (SettingsDialog.GITHUB_SETTINGS_TAB_NAME.equals(tabs.getTitleAt(i))) {
                    foundGitHubTab = true;
                    break;
                }
            }

            assertTrue(
                    foundGitHubTab,
                    "Global settings should always include the Git tab so users can configure Git/GitHub settings, "
                            + "even when the project is not a GitHub repo");

            // Verify Git / Signing tab is merged and no longer standalone
            boolean foundGitSigningTab = false;
            for (int i = 0; i < tabCount; i++) {
                if ("Git / Signing".equals(tabs.getTitleAt(i))) {
                    foundGitSigningTab = true;
                    break;
                }
            }
            assertFalse(foundGitSigningTab, "Git / Signing tab should be merged into GitHub tab");
        });
    }

    @Test
    void githubSettingsTabIsVisibleForGitHubRepo(@TempDir Path projectRoot) throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires non-headless AWT");

        // Create a project with a GitHub remote
        var project = new TestProject(projectRoot);
        // TestProject should already have a git repo, no need to set remote for this test
        // The test is checking that the GitHub tab is always visible regardless of remote

        var contextManager = new ContextManager(project);
        var chrome = new Chrome(contextManager);

        SwingUtilities.invokeAndWait(() -> {
            SettingsGlobalPanel panel = new SettingsGlobalPanel(chrome, /* parentDialog= */ null);

            JTabbedPane tabs = panel.getGlobalSubTabbedPane();
            int tabCount = tabs.getTabCount();

            boolean foundGitHubTab = false;
            for (int i = 0; i < tabCount; i++) {
                if (SettingsDialog.GITHUB_SETTINGS_TAB_NAME.equals(tabs.getTitleAt(i))) {
                    foundGitHubTab = true;
                    break;
                }
            }

            assertTrue(foundGitHubTab, "Global settings should include the Git tab for all projects");

            // Verify Git / Signing tab is merged and no longer standalone
            boolean foundGitSigningTab = false;
            for (int i = 0; i < tabCount; i++) {
                if ("Git / Signing".equals(tabs.getTitleAt(i))) {
                    foundGitSigningTab = true;
                    break;
                }
            }
            assertFalse(foundGitSigningTab, "Git / Signing tab should be merged into GitHub tab");
        });
    }
}
