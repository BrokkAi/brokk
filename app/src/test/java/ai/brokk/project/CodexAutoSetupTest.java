package ai.brokk.project;

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
}
