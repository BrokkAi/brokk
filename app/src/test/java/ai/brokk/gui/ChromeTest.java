package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import ai.brokk.ContextManager;
import ai.brokk.IAnalyzerWrapper;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.project.AbstractProject;
import ai.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChromeTest {
    private static final String PREFS_ROOT = "io.github.jbellis.brokk";
    private static final String PREFS_PROJECTS = "projects";
    private static final String PREF_KEY_SIDEBAR_OPEN = "sidebarOpen";

    @TempDir
    Path tempDir;

    private @Nullable TestProject project;
    private @Nullable Preferences projectPrefs;
    private @Nullable ContextManager contextManager;
    private @Nullable Chrome chrome;

    @BeforeEach
    void setUp() throws Exception {
        project = new TestProject(tempDir);
        // Ensure .brokk directory exists to satisfy onboarding checks in Chrome
        Files.createDirectories(tempDir.resolve(AbstractProject.BROKK_DIR));

        String projKey = project.getRoot()
                .toString()
                .replace('/', '_')
                .replace('\\', '_')
                .replace(':', '_');
        projectPrefs =
                Preferences.userRoot().node(PREFS_ROOT).node(PREFS_PROJECTS).node(projKey);

        contextManager = new ContextManager(project);
        // Set a default analyzer wrapper to avoid NPE during closeAsync/shutdown
        contextManager.setAnalyzerWrapper(new IAnalyzerWrapper() {
            @Override
            public java.util.concurrent.CompletableFuture<IAnalyzer> updateFiles(
                    java.util.Set<ai.brokk.analyzer.ProjectFile> relevantFiles) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }

            @Override
            public IAnalyzer get() {
                throw new UnsupportedOperationException();
            }

            @Override
            public @Nullable IAnalyzer getNonBlocking() {
                return null;
            }
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (chrome != null) {
            SwingUtilities.invokeAndWait(() -> chrome.close());
            chrome = null;
        } else if (contextManager != null) {
            // Chrome.close() also closes contextManager, so only close if chrome was null
            contextManager.close();
        }

        if (projectPrefs != null) {
            try {
                projectPrefs.remove(PREF_KEY_SIDEBAR_OPEN);
                projectPrefs.flush();
            } catch (Exception ignored) {
            }
            projectPrefs = null;
        }
    }

    @Test
    void testChromeInstantiation() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "Skipping GUI test in headless environment");

        // Chrome constructor must run on EDT
        SwingUtilities.invokeAndWait(() -> {
            chrome = new Chrome(contextManager);
        });

        assertNotNull(chrome, "Chrome should be successfully instantiated");
        assertNotNull(chrome.getFrame(), "Chrome frame should be created");
    }

    @Test
    void testCollapsedSidebarNeverBelowIconStripWidth() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "Skipping GUI test in headless environment");
        assertNotNull(projectPrefs);

        projectPrefs.putBoolean(PREF_KEY_SIDEBAR_OPEN, false);
        projectPrefs.flush();

        SwingUtilities.invokeAndWait(() -> {
            chrome = new Chrome(contextManager);
        });

        SwingUtilities.invokeAndWait(() -> {
            int divider = chrome.getHorizontalSplitPane().getDividerLocation();
            assertTrue(divider >= 40, "Collapsed sidebar divider should keep at least the 40px icon strip visible");
        });
    }
}
