package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainProjectGlobalConfigDirCacheTest {

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
    void testGlobalConfigDirIsCached(@TempDir Path tempDir) throws IOException {
        Path dir1 = tempDir.resolve("dir1");
        Path dir2 = tempDir.resolve("dir2");

        System.setProperty("brokk.test.mode", "true");
        System.setProperty("brokk.test.sandbox.root", dir1.toString());

        // First access: should resolve to dir1/Brokk
        MainProject.setTheme("theme1");

        Path propertiesFile1 = dir1.resolve("Brokk").resolve("brokk.properties");
        assertTrue(Files.exists(propertiesFile1), "Properties should be written to dir1");

        // Change sandbox root: the cache should ensure we stick to dir1
        System.setProperty("brokk.test.sandbox.root", dir2.toString());
        MainProject.setTheme("theme2");

        Path propertiesFile2 = dir2.resolve("Brokk").resolve("brokk.properties");
        assertFalse(Files.exists(propertiesFile2), "Properties should NOT be written to dir2 because of caching");

        // Verify content in dir1 reflects the update because we are still using that path
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(propertiesFile1)) {
            props.load(reader);
        }
        assertEquals(
                "theme2", props.getProperty("theme"), "Theme should have been updated in the original cached path");
    }
}
