package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.brokk.AbstractService.ModelConfig;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that verify the default code model configuration fallback when no user-specified
 * codeConfig exists in global properties.
 */
class MainProjectDefaultCodeModelTest {

    @TempDir
    Path tempDir;

    private Properties originalGlobalPropertiesCache;
    private MainProject testProject;

    @BeforeEach
    void setUp() throws Exception {
        originalGlobalPropertiesCache = captureGlobalPropertiesCache();
        setGlobalPropertiesCache(new Properties());

        Git.init().setDirectory(tempDir.toFile()).call().close();
        testProject = new MainProject(tempDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (testProject != null) {
            testProject.close();
        }
        setGlobalPropertiesCache(originalGlobalPropertiesCache);

        if (Files.exists(tempDir)) {
            Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }

    @Test
    void testDefaultCodeModelIsClaudeHaiku45() {
        ModelConfig config = testProject.getCodeModelConfig();

        assertNotNull(config, "Code model config should not be null");
        assertEquals(
                "claude-haiku-4-5",
                config.name(),
                "Default code model should be claude-haiku-4-5 when no codeConfig is set");
        assertEquals(Service.ReasoningLevel.DEFAULT, config.reasoning(), "Default reasoning level should be DEFAULT");
        assertEquals(Service.ProcessingTier.DEFAULT, config.tier(), "Default processing tier should be DEFAULT");
    }

    private Properties captureGlobalPropertiesCache() throws Exception {
        Field cacheField = MainProject.class.getDeclaredField("globalPropertiesCache");
        cacheField.setAccessible(true);
        Properties cached = (Properties) cacheField.get(null);
        return cached != null ? (Properties) cached.clone() : null;
    }

    private void setGlobalPropertiesCache(Properties props) throws Exception {
        Field cacheField = MainProject.class.getDeclaredField("globalPropertiesCache");
        cacheField.setAccessible(true);
        cacheField.set(null, props != null ? (Properties) props.clone() : null);
    }
}
