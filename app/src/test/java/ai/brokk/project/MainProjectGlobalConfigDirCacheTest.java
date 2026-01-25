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
    }

    @Test
    void testGlobalConfigDirResolvesLiveWithoutCaching(@TempDir Path tempDir) throws IOException {
        Path dir1 = tempDir.resolve("dir1");
        Path dir2 = tempDir.resolve("dir2");

        System.setProperty("brokk.test.mode", "true");
        System.setProperty("brokk.test.sandbox.root", dir1.toString());

        // First access: should resolve to dir1 based on current system property
        MainProject.setTheme("theme1");

        long pid = ProcessHandle.current().pid();
        Path propertiesFile1 =
                dir1.resolve("brokk-test-" + pid).resolve("Brokk").resolve("brokk.properties");
        assertTrue(Files.exists(propertiesFile1), "Properties should be written to dir1");

        // Verify sandbox isolates from real config directory (platform-specific paths)
        String userHome = System.getProperty("user.home");
        String sandboxPath = propertiesFile1.getParent().getParent().toString();
        assertFalse(
                sandboxPath.contains(userHome + "/.config/Brokk")
                        || sandboxPath.contains(userHome + "/Library/Application Support/Brokk")
                        || sandboxPath.contains(userHome + "\\AppData\\Roaming\\Brokk"),
                "Sandbox path should not be in user's real config directory: " + sandboxPath);

        // Change sandbox root: without caching, the next write should go to dir2
        System.setProperty("brokk.test.sandbox.root", dir2.toString());
        MainProject.setTheme("theme2");

        Path propertiesFile2 =
                dir2.resolve("brokk-test-" + pid).resolve("Brokk").resolve("brokk.properties");
        assertTrue(Files.exists(propertiesFile2), "Properties should be written to dir2 after sandbox root change");

        // Verify content in dir2 reflects the latest update
        Properties props2 = new Properties();
        try (var reader = Files.newBufferedReader(propertiesFile2)) {
            props2.load(reader);
        }
        assertEquals("theme2", props2.getProperty("theme"), "Theme should be updated in the new path");

        // Verify content in dir1 was NOT updated by the second call
        Properties props1 = new Properties();
        try (var reader = Files.newBufferedReader(propertiesFile1)) {
            props1.load(reader);
        }
        assertEquals("theme1", props1.getProperty("theme"), "Theme in original path should remain theme1");
    }
}
