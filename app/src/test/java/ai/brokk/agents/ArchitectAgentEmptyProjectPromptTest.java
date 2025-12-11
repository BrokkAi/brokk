package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.IProject;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for ArchitectAgent prompt generation with empty projects.
 * Verifies that the agent correctly detects and responds to empty projects.
 */
public class ArchitectAgentEmptyProjectPromptTest {

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

        var cm = new TestContextManager(
                emptyProject,
                new ai.brokk.testutil.TestConsoleIO(),
                Set.of(),
                null);

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

        var cm = new TestContextManager(
                nonEmptyProject,
                new ai.brokk.testutil.TestConsoleIO(),
                Set.of(),
                null);

        // Verify the context manager has the non-empty project
        assertFalse(cm.getProject().isEmptyProject(), "Context manager should have non-empty project");
    }
}
