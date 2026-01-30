package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.project.MainProject;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that ContextManager can be initialized in headless mode without touching Swing/EDT.
 *
 * This protects against regressions where GUI-only code is executed during headless initialization
 * (which previously caused AWT-related failures such as "AWT blocker activation interrupted").
 */
public class ContextManagerHeadlessTest {

    @TempDir
    Path tempDir;

    @Test
    void createHeadlessCompletesWithoutSwing() {
        // Force headless AWT environment for the duration of this test to catch accidental Swing usage.
        // Note: setting the system property here affects only this JVM; tests should restore it if necessary.
        System.setProperty("java.awt.headless", "true");

        var project = MainProject.forTests(tempDir);

        // Use try-with-resources so ContextManager is properly closed and so `cm` is effectively final
        try (ContextManager cm = new ContextManager(project)) {
            // The call should complete without throwing and should mark the manager as headless.
            assertDoesNotThrow(() -> cm.createHeadless(), "createHeadless() must not throw in headless mode");

            assertTrue(cm.isHeadlessMode(), "ContextManager should be in headless mode after createHeadless()");
            assertNotNull(cm.getIo(), "IConsoleIO should be set for headless mode");
            // Basic sanity: current session id must be non-null
            assertNotNull(cm.getCurrentSessionId(), "Current session id should be available after initialization");
        } finally {
            // Cleanup: don't leave the property set for other tests.
            System.clearProperty("java.awt.headless");
        }
    }
}
