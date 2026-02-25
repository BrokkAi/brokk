package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AbstractService;
import ai.brokk.ContextManager;
import ai.brokk.Service;
import ai.brokk.agents.BuildAgent;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.TestConsoleIO;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class HeadlessBuildDetailsTest {

    private MainProject.LlmProxySetting originalProxySetting;

    /**
     * Stub service provider to avoid real LLM/Proxy calls during ContextManager initialization.
     */
    private static class StubServiceProvider implements Service.Provider {
        private @Nullable IProject project;

        @Override
        public AbstractService get() {
            return new AbstractService(project) {
                @Override
                public float getUserBalance() {
                    return 100.0f;
                }

                @Override
                public void sendFeedback(
                        String category,
                        String feedbackText,
                        boolean includeDebugLog,
                        @Nullable java.io.File screenshotFile) {
                    // No-op for stub
                }

                @Override
                public JsonNode reportClientException(JsonNode exceptionReport) {
                    return null;
                }
            };
        }

        @Override
        public void reinit(IProject project) {
            this.project = project;
        }
    }

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

        try (MainProject project = new MainProject(tempDir);
                ContextManager cm = new ContextManager(project, new StubServiceProvider())) {
            // Ensure no details exist
            assertFalse(project.loadBuildDetails().isPresent());

            TestConsoleIO io = new TestConsoleIO();

            // 2. Call createHeadless with EMPTY override
            cm.createHeadless(BuildAgent.BuildDetails.EMPTY, true, io);

            // 3. Wait for inference (inference runs via submitBackgroundTask in ensureBuildDetailsAsync)
            var future = project.getBuildDetailsFuture();
            BuildAgent.BuildDetails details = future.get(5, TimeUnit.SECONDS);

            // Validate inference was attempted
            assertTrue(project.hasBuildDetails());
        }
    }

    @Test
    void testEmptyProjectSkipsInference(@TempDir Path tempDir) throws Exception {
        // 1. Setup a completely empty project
        try (MainProject project = new MainProject(tempDir);
                ContextManager cm = new ContextManager(project, new StubServiceProvider())) {
            assertTrue(project.isEmptyProject());

            TestConsoleIO io = new TestConsoleIO();

            // 2. Call createHeadless
            cm.createHeadless(BuildAgent.BuildDetails.EMPTY, true, io);

            // 3. Assert it immediate completes with EMPTY and no inference task is stuck
            BuildAgent.BuildDetails details = project.awaitBuildDetails();
            assertEquals(BuildAgent.BuildDetails.EMPTY, details);
        }
    }

    @Test
    void testExplicitOverridePreventsInference(@TempDir Path tempDir) throws Exception {
        // 1. Setup minimal project
        Files.writeString(tempDir.resolve("README.md"), "test");
        try (MainProject project = new MainProject(tempDir);
                ContextManager cm = new ContextManager(project, new StubServiceProvider())) {

            BuildAgent.BuildDetails override =
                    new BuildAgent.BuildDetails("mvn compile", "mvn test", "mvn test -Dtest={{classes}}", Set.of());

            TestConsoleIO io = new TestConsoleIO();

            // 2. Call createHeadless with non-empty override
            cm.createHeadless(override, true, io);

            // 3. Assert override is used immediately
            BuildAgent.BuildDetails details = project.awaitBuildDetails();
            assertEquals("mvn compile", details.buildLintCommand());
            assertEquals(override, details);

            // Verify it didn't run inference by checking project.properties (inference saves to disk)
            assertFalse(project.loadBuildDetails().isPresent());
        }
    }
}
