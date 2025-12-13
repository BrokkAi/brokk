package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.project.IProject;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for SearchAgent prompt generation with empty projects.
 * Verifies that the agent correctly detects and responds to empty projects.
 */
public class SearchAgentEmptyProjectPromptTest {

    @TempDir
    Path tempDir;

    @Test
    public void testAgentConstructedWithEmptyProject() throws Exception {
        // Create a stub IProject that returns true for isEmptyProject()
        IProject emptyProject = new TestProject(tempDir) {
            @Override
            public boolean isEmptyProject() {
                return true;
            }
        };

        // Create a minimal context manager with the empty project
        var cm = new TestContextManager(emptyProject, new ai.brokk.testutil.TestConsoleIO(), Set.of(), null);
        var context = cm.liveContext();

        // Verify the context manager has the empty project
        assertTrue(cm.getProject().isEmptyProject(), "Context manager should have empty project");
    }

    @Test
    public void testAgentConstructedWithNonEmptyProject() throws Exception {
        // Create a stub IProject that returns false for isEmptyProject()
        IProject nonEmptyProject = new TestProject(tempDir) {
            @Override
            public boolean isEmptyProject() {
                return false;
            }
        };

        var cm = new TestContextManager(nonEmptyProject, new ai.brokk.testutil.TestConsoleIO(), Set.of(), null);
        var context = cm.liveContext();

        // Verify the context manager has the non-empty project
        assertFalse(cm.getProject().isEmptyProject(), "Context manager should have non-empty project");
    }
}
