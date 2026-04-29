package ai.brokk.project;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AbstractService.ModelConfig;
import ai.brokk.project.ModelProperties.ModelType;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexAutoSetupTest {

    private String originalTestMode;
    private String originalSandboxRoot;

    @BeforeEach
    void setUp() {
        originalTestMode = System.getProperty("brokk.test.mode");
        originalSandboxRoot = System.getProperty("brokk.test.sandbox.root");
        MainProject.resetGlobalConfigCachesForTests();
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
        MainProject.resetGlobalConfigCachesForTests();
    }

    @Test
    void testModelPropertiesExposesCodexVendor() {
        Set<String> vendors = ModelProperties.getAvailableVendors();
        assertTrue(vendors.contains("OpenAI - Codex"), "Available vendors should include 'OpenAI - Codex'");

        Map<ModelType, ModelConfig> codexModels = ModelProperties.getVendorModels("OpenAI - Codex");
        assertNotNull(codexModels, "Should return model map for Codex vendor");

        // Check for all internal ModelTypes (excluding CODE and ARCHITECT as per ModelProperties logic)
        for (ModelType type : ModelType.values()) {
            if (type == ModelType.CODE || type == ModelType.ARCHITECT) {
                continue;
            }
            assertTrue(codexModels.containsKey(type), "Codex vendor map should contain config for " + type);
        }
    }

    @Test
    void testMainProjectCodexPersistence(@TempDir Path tempDir) {
        // Setup sandbox
        System.setProperty("brokk.test.mode", "true");
        System.setProperty("brokk.test.sandbox.root", tempDir.toString());

        // 1. Verify initial state
        assertEquals("", MainProject.getOtherModelsVendorPreference(), "Vendor preference should be empty initially");

        // 2. Simulate Auto-Setup actions
        MainProject.setOtherModelsVendorPreference("OpenAI - Codex");

        // Use a MainProject instance to set model config
        Path projectRoot = tempDir.resolve("dummy-project");
        // Ensure directory exists
        projectRoot.toFile().mkdirs();

        MainProject mainProject = new MainProject(projectRoot);

        ModelConfig testConfig = new ModelConfig("gpt-5.1-codex-max-oauth");
        mainProject.setModelConfig(ModelType.CODE, testConfig);

        // 3. Verify in-memory updates
        assertEquals("OpenAI - Codex", MainProject.getOtherModelsVendorPreference());
        assertEquals(
                "gpt-5.1-codex-max-oauth",
                mainProject.getModelConfig(ModelType.CODE).name());

        // 4. Reset caches to force reload from disk
        MainProject.resetGlobalConfigCachesForTests();

        // 5. Verify persistence
        assertEquals(
                "OpenAI - Codex", MainProject.getOtherModelsVendorPreference(), "Vendor preference should persist");

        // Check persistence of model config
        ModelConfig loadedConfig = mainProject.getModelConfig(ModelType.CODE);
        assertEquals("gpt-5.1-codex-max-oauth", loadedConfig.name(), "Model config should persist");
    }

    @Test
    void testCodexFlagSubstitutesModelConfigAtReadTime(@TempDir Path tempDir) {
        System.setProperty("brokk.test.mode", "true");
        System.setProperty("brokk.test.sandbox.root", tempDir.toString());
        MainProject.resetGlobalConfigCachesForTests();

        Path projectRoot = tempDir.resolve("dummy-project");
        projectRoot.toFile().mkdirs();
        MainProject project = new MainProject(projectRoot);

        // Persist non-Codex preferences for a directly-selected role and a vendor-driven one.
        project.setModelConfig(ModelType.CODE, new ModelConfig("claude-sonnet-4-6"));
        project.setModelConfig(ModelType.SUMMARIZE, new ModelConfig("gemini-3-flash-preview"));

        MainProject.setOpenAiCodexOauthConnected(false);
        assertEquals("claude-sonnet-4-6", project.getModelConfig(ModelType.CODE).name());
        assertEquals(
                "gemini-3-flash-preview", project.getModelConfig(ModelType.SUMMARIZE).name());

        MainProject.setOpenAiCodexOauthConnected(true);
        MainProject.setRestrictToOauthModelsWhenConnected(true);
        assertEquals(
                ModelProperties.CODEX_OAUTH_CODE_CONFIG.name(),
                project.getModelConfig(ModelType.CODE).name(),
                "CODE should substitute to Codex OAuth config when restrict flag is active");
        var codexVendorMap = requireNonNull(ModelProperties.getVendorModels("OpenAI - Codex"));
        assertEquals(
                requireNonNull(codexVendorMap.get(ModelType.SUMMARIZE)).name(),
                project.getModelConfig(ModelType.SUMMARIZE).name(),
                "SUMMARIZE should substitute via Codex vendor map when restrict flag is active");

        MainProject.setRestrictToOauthModelsWhenConnected(false);
        assertEquals(
                "claude-sonnet-4-6",
                project.getModelConfig(ModelType.CODE).name(),
                "Persisted CODE returns when restrict flag is disabled");
        assertEquals(
                "gemini-3-flash-preview",
                project.getModelConfig(ModelType.SUMMARIZE).name(),
                "Persisted SUMMARIZE returns when restrict flag is disabled");

        // Persistence sanity: substitution never wrote anything to disk.
        MainProject.resetGlobalConfigCachesForTests();
        assertEquals("claude-sonnet-4-6", project.getModelConfig(ModelType.CODE).name());
    }
}
