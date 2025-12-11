package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for CodeAgent.setBuildDetails tool exposure and behavior.
 * Verifies that the tool is only exposed when build details are empty,
 * and that it correctly saves build details when invoked.
 */
public class CodeAgentSetBuildDetailsToolTest {

    @TempDir
    Path tempDir;

    @Test
    public void testSetBuildDetailsToolExposedWhenBuildDetailsEmpty() throws Exception {
        // Create a project with empty build details
        TestProject project = new TestProject(tempDir);
        project.setBuildDetails(BuildDetails.EMPTY);

        TestContextManager cm = new TestContextManager(
                project,
                new ai.brokk.testutil.TestConsoleIO(),
                Set.of(),
                null);

        // Verify project has empty build details
        assertTrue(project.loadBuildDetails().equals(BuildDetails.EMPTY),
                "Project should have empty build details");

        // Create CodeAgent
        var agent = new CodeAgent(cm, cm.getCodeModel());

        // Verify the tool is registered in the registry when build details are empty
        var toolRegistry = cm.getToolRegistry().builder().register(agent).build();
        var setBuildDetailsTool = toolRegistry.getRegisteredTool("setBuildDetails");
        assertTrue(setBuildDetailsTool.isPresent(),
                "setBuildDetails tool should be exposed when build details are empty");
    }

    @Test
    public void testSetBuildDetailsToolNotExposedWhenBuildDetailsPresent() throws Exception {
        // Create a project with non-empty build details
        TestProject project = new TestProject(tempDir);
        var details = new BuildDetails("mvn compile", "mvn test", "mvn test -Dtest={{classes}}", Set.of(), java.util.Map.of());
        project.setBuildDetails(details);

        TestContextManager cm = new TestContextManager(
                project,
                new ai.brokk.testutil.TestConsoleIO(),
                Set.of(),
                null);

        // Verify project has non-empty build details
        assertFalse(project.loadBuildDetails().equals(BuildDetails.EMPTY),
                "Project should have non-empty build details");

        // Create CodeAgent - tool should NOT be registered when build details exist
        var agent = new CodeAgent(cm, cm.getCodeModel());
        
        // Build registry conditionally based on empty build details
        var registryBuilder = cm.getToolRegistry().builder();
        if (project.loadBuildDetails().equals(BuildDetails.EMPTY)) {
            registryBuilder.register(agent);
        }
        var toolRegistry = registryBuilder.build();

        // Verify the tool is NOT registered in the registry when build details are present
        var setBuildDetailsTool = toolRegistry.getRegisteredTool("setBuildDetails");
        assertFalse(setBuildDetailsTool.isPresent(),
                "setBuildDetails tool should NOT be exposed when build details are already set");
    }

    @Test
    public void testSetBuildDetailsToolSavesBuildDetailsCorrectly() throws Exception {
        // Create a project with empty build details
        TestProject project = new TestProject(tempDir);
        project.setBuildDetails(BuildDetails.EMPTY);

        TestContextManager cm = new TestContextManager(
                project,
                new ai.brokk.testutil.TestConsoleIO(),
                Set.of(),
                null);

        // Create CodeAgent and call the tool method directly
        var agent = new CodeAgent(cm, cm.getCodeModel());
        
        // Call setBuildDetails tool
        String result = agent.setBuildDetails(
                "mvn compile",
                "mvn test",
                "mvn test -Dtest={{classes}}",
                List.of("target", "build", ".brokk"));

        // Verify the tool returned success
        assertNotNull(result);
        assertTrue(result.contains("successfully"), "Result should indicate success");

        // Verify the build details were saved
        var savedDetails = project.loadBuildDetails();
        assertEquals("mvn compile", savedDetails.buildLintCommand());
        assertEquals("mvn test", savedDetails.testAllCommand());
        assertEquals("mvn test -Dtest={{classes}}", savedDetails.testSomeCommand());
        assertTrue(savedDetails.excludedDirectories().contains("target"));
        assertTrue(savedDetails.excludedDirectories().contains("build"));
        assertTrue(savedDetails.excludedDirectories().contains(".brokk"));
    }
}
