package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import ai.brokk.ContextManager;
import ai.brokk.IAnalyzerWrapper;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.project.AbstractProject;
import ai.brokk.testutil.TestProject;
import ai.brokk.gui.SwingUtil;
import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class ChromeTest {

    @TempDir
    Path tempDir;

    private @Nullable TestProject project;
    private @Nullable ContextManager contextManager;
    private @Nullable Chrome chrome;

    @BeforeEach
    void setUp() throws Exception {
        project = new TestProject(tempDir);
        // Ensure .brokk directory exists to satisfy onboarding checks in Chrome
        Files.createDirectories(tempDir.resolve(AbstractProject.BROKK_DIR));

        contextManager = new ContextManager(project);
        // Set a default analyzer wrapper to avoid NPE during closeAsync/shutdown
        contextManager.setAnalyzerWrapper(new IAnalyzerWrapper() {
            @Override
            public java.util.concurrent.CompletableFuture<IAnalyzer> updateFiles(java.util.Set<ai.brokk.analyzer.ProjectFile> relevantFiles) {
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
}
