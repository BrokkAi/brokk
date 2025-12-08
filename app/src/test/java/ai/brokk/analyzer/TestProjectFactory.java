package ai.brokk.analyzer;

import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import java.io.File;
import java.util.Set;

/**
 * Factory for creating test projects with minimal configuration.
 */
public class TestProjectFactory {

    /**
     * Creates a minimal test project for integration testing.
     *
     * @param projectDir the directory to use as the project root
     * @return an IProject instance configured for testing
     */
    public static IProject createTestProject(File projectDir) {
        // Create a MainProject instance with the test directory as root
        // This assumes MainProject has a constructor or static factory accepting a File/Path
        try {
            return new MainProject(projectDir.toPath());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test project: " + e.getMessage(), e);
        }
    }
}
