package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.project.TestConfigHelper;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceCodexGatingTest {

    private String originalTestMode;
    private String originalSandboxRoot;

    @BeforeEach
    void setUp() {
        originalTestMode = System.getProperty("brokk.test.mode");
        originalSandboxRoot = System.getProperty("brokk.test.sandbox.root");
        TestConfigHelper.resetGlobalConfigCaches();
    }

    @AfterEach
    void tearDown() {
        if (originalTestMode != null) {
            System.setProperty("brokk.test.mode", originalTestMode);
        } else {
            System.clearProperty("brokk.test.mode");
        }
        if (originalSandboxRoot != null) {
            System.setProperty("brokk.test.sandbox.root", originalSandboxRoot);
        } else {
            System.clearProperty("brokk.test.sandbox.root");
        }
        TestConfigHelper.resetGlobalConfigCaches();
    }

    @Test
    void testCodexFiltering(@TempDir Path tempDir) {
        // Setup sandbox
        System.setProperty("brokk.test.mode", "true");
        System.setProperty("brokk.test.sandbox.root", tempDir.toString());

        // We use a dummy project. The MainProject static methods will rely on the sandbox/test-mode.
        Path projectRoot = tempDir.resolve("dummy-project");
        projectRoot.toFile().mkdirs();
        MainProject project = new MainProject(projectRoot);

        GatingTestService service = new GatingTestService(project);

        // Setup models
        service.addModel("Normal Model", "normal-loc", false);
        service.addModel("Codex Model", "codex-loc", true);
        service.addModel("gpt-5.4-oauth", "oauth-loc", false);
        service.addModel("gpt-5.2-oauth", "oauth-5.2-loc", false);
        service.addModel("gpt-5.2-codex-fast-oauth", "oauth-5.2-codex-loc", false);
        // Non-OAuth Codex API variant: gated by is_codex, not by the OAuth-prefix filter.
        // Shares the gpt-5.2-codex prefix used to exclude OAuth variants but must remain
        // governed solely by isCodexModel(); the OAuth restriction never applies to it
        // because it lacks the -oauth suffix.
        service.addModel("gpt-5.2-codex", "codex-5.2-loc", true);

        // 1. Not connected
        MainProject.setOpenAiCodexOauthConnected(false);
        var available = service.getAvailableModels();
        assertTrue(available.containsKey("Normal Model"));
        assertFalse(available.containsKey("Codex Model"));
        assertTrue(available.containsKey("gpt-5.4-oauth"));
        assertTrue(available.containsKey("gpt-5.2-oauth"));
        assertTrue(available.containsKey("gpt-5.2-codex-fast-oauth"));
        assertFalse(available.containsKey("gpt-5.2-codex"));
        assertFalse(service.isCodexModel("Normal Model"));
        assertTrue(service.isCodexModel("Codex Model"));
        assertTrue(service.isCodexModel("gpt-5.2-codex"));

        // 2. Connected with OAuth restriction disabled: all models visible
        MainProject.setOpenAiCodexOauthConnected(true);
        MainProject.setRestrictToOauthModelsWhenConnected(false);
        available = service.getAvailableModels();
        assertTrue(available.containsKey("Normal Model"));
        assertTrue(available.containsKey("Codex Model"));
        assertTrue(available.containsKey("gpt-5.4-oauth"));
        assertTrue(available.containsKey("gpt-5.2-oauth"));
        assertTrue(available.containsKey("gpt-5.2-codex-fast-oauth"));
        assertTrue(available.containsKey("gpt-5.2-codex"));

        // 3. Connected with OAuth restriction enabled (default): only allowed -oauth-suffixed models visible.
        // gpt-5.2-codex (no -oauth suffix) is hidden by the suffix check, independently of the
        // gpt-5.2-codex- prefix exclusion that targets OAuth variants.
        MainProject.setRestrictToOauthModelsWhenConnected(true);
        available = service.getAvailableModels();
        assertFalse(available.containsKey("Normal Model"));
        assertFalse(available.containsKey("Codex Model"));
        assertTrue(available.containsKey("gpt-5.4-oauth"));
        assertTrue(available.containsKey("gpt-5.2-oauth"));
        assertFalse(available.containsKey("gpt-5.2-codex-fast-oauth"));
        assertFalse(available.containsKey("gpt-5.2-codex"));
    }

    /**
     * Subclass of AbstractService to allow populating protected model maps.
     */
    static class GatingTestService extends AbstractService {
        GatingTestService(IProject project) {
            super(project);
            // Re-initialize maps to mutable hashmaps for testing
            this.modelLocations = new HashMap<>();
            this.modelInfoMap = new HashMap<>();
        }

        void addModel(String name, String location, boolean isCodex) {
            modelLocations.put(name, location);
            Map<String, Object> info = new HashMap<>();
            if (isCodex) {
                info.put("is_codex", true);
            }
            modelInfoMap.put(name, info);
        }

        @Override
        public float getUserBalance() {
            return 0;
        }

        @Override
        public void sendFeedback(String category, String feedbackText, boolean includeDebugLog, File screenshotFile) {}

        @Override
        public JsonNode reportClientException(JsonNode exceptionReport) {
            return null;
        }
    }
}
