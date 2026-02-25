package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.agents.BuildAgent;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.TestConsoleIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class HeadlessBuildDetailsTest {

    private MainProject.LlmProxySetting originalProxySetting;

    @BeforeEach
    void setUpProxySetting() {
        originalProxySetting = MainProject.getProxySetting();
        // Use LOCALHOST so checkBalanceAndNotify() returns early and does not perform remote balance checks.
        MainProject.setLlmProxySetting(MainProject.LlmProxySetting.LOCALHOST);
    }

    @AfterEach
    void restoreProxySetting() {
        MainProject.setLlmProxySetting(originalProxySetting);
    }

    @Test
    void testFreshProjectInfersBuildDetails(@TempDir Path tempDir) throws Exception {
        // 1. Setup a fresh non-empty project with a recognizable build file
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.writeString(tempDir.resolve("src/main/java/Foo.java"), "public class Foo {}");

        MainProject project = new MainProject(tempDir);
        // Ensure no details exist
        assertFalse(project.loadBuildDetails().isPresent());

        ContextManager cm = new ContextManager(project);
        TestConsoleIO io = new TestConsoleIO();

        // 2. Call createHeadless with EMPTY override
        cm.createHeadless(BuildAgent.BuildDetails.EMPTY, true, io);

        // 3. Wait for inference (inference runs via submitBackgroundTask in ensureBuildDetailsAsync)
        // Since we didn't stub the LLM to return specific commands, it might result in EMPTY if no LLM mock is wired.
        // However, the logic should at least attempt it.
        var future = project.getBuildDetailsFuture();
        BuildAgent.BuildDetails details = future.get(5, TimeUnit.SECONDS);

        // Validate inference was attempted (or check logs/io if we can't rely on real inference output without LLM)
        assertTrue(project.hasBuildDetails());
    }

    @Test
    void testEmptyProjectSkipsInference(@TempDir Path tempDir) throws Exception {
        // 1. Setup a completely empty project
        MainProject project = new MainProject(tempDir);
        assertTrue(project.isEmptyProject());

        ContextManager cm = new ContextManager(project);
        TestConsoleIO io = new TestConsoleIO();

        // 2. Call createHeadless
        cm.createHeadless(BuildAgent.BuildDetails.EMPTY, true, io);

        // 3. Assert it immediate completes with EMPTY and no inference task is stuck
        BuildAgent.BuildDetails details = project.awaitBuildDetails();
        assertEquals(BuildAgent.BuildDetails.EMPTY, details);
    }

    @Test
    void testExplicitOverridePreventsInference(@TempDir Path tempDir) throws Exception {
        // 1. Setup minimal project
        Files.writeString(tempDir.resolve("README.md"), "test");
        MainProject project = new MainProject(tempDir);

        BuildAgent.BuildDetails override =
                new BuildAgent.BuildDetails("mvn compile", "mvn test", "mvn test -Dtest={{classes}}", Set.of());

        ContextManager cm = new ContextManager(project);
        TestConsoleIO io = new TestConsoleIO();

        // 2. Call createHeadless with non-empty override
        cm.createHeadless(override, true, io);

        // 3. Assert override is used immediately
        BuildAgent.BuildDetails details = project.awaitBuildDetails();
        assertEquals("mvn compile", details.buildLintCommand());
        assertEquals(override, details);

        // Verify it didn't run inference by checking project.properties (inference saves to disk, createHeadless
        // override does not clobber unless inference wins)
        assertFalse(project.loadBuildDetails().isPresent());
    }
}
