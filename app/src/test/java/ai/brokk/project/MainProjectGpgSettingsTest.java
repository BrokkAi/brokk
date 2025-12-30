package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MainProjectGpgSettingsTest {

    @BeforeEach
    void setUp() {
        synchronized (MainProject.class) {
            MainProject.globalPropertiesCache = new Properties();
        }
    }

    @AfterEach
    void tearDown() {
        synchronized (MainProject.class) {
            MainProject.globalPropertiesCache = null;
        }
    }

    @Test
    void testGpgSigningSettingsPersistence() throws IOException {
        // Defaults
        assertFalse(MainProject.isGpgCommitSigningEnabled());
        assertEquals("", MainProject.getGpgSigningKey());

        // Set values
        MainProject.setGpgCommitSigningEnabled(true);
        MainProject.setGpgSigningKey("ABC12345");

        assertTrue(MainProject.isGpgCommitSigningEnabled());
        assertEquals("ABC12345", MainProject.getGpgSigningKey());

        // Verify values are in the cache
        synchronized (MainProject.class) {
            Properties cache = MainProject.globalPropertiesCache;
            assertTrue(cache != null && cache.containsKey("gpgCommitSigningEnabled"));
            assertEquals("true", cache.getProperty("gpgCommitSigningEnabled"));
            assertEquals("ABC12345", cache.getProperty("gpgSigningKey"));
        }

        assertTrue(MainProject.isGpgCommitSigningEnabled());
        assertEquals("ABC12345", MainProject.getGpgSigningKey());

        // Verify empty key removal
        MainProject.setGpgSigningKey("");
        assertEquals("", MainProject.getGpgSigningKey());
    }
}
