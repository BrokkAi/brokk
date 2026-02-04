package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.util.Environment;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServiceFeedbackMetadataTest {

    @Test
    void testBuildFeedbackMetadata() {
        Map<String, String> metadata = Service.buildFeedbackMetadata();

        assertNotNull(metadata, "Metadata map should not be null");

        // Check keys
        assertTrue(metadata.containsKey("app_version"), "Metadata should contain app_version");
        assertTrue(metadata.containsKey("os_description"), "Metadata should contain os_description");
        assertTrue(metadata.containsKey("jre_description"), "Metadata should contain jre_description");

        // Validate Version
        assertEquals(BuildInfo.version, metadata.get("app_version"), "app_version should match BuildInfo.version");
        assertNotNull(BuildInfo.version, "BuildInfo.version must not be null");
        assertFalse(BuildInfo.version.isBlank(), "BuildInfo.version should not be blank");

        // Validate OS Description
        String os = metadata.get("os_description");
        assertNotNull(os, "OS description must not be null");
        assertFalse(os.isBlank(), "OS description should not be blank");

        String actualEnvOs = Environment.getOsDescription();
        if (actualEnvOs == null || actualEnvOs.isBlank()) {
            assertEquals("unknown OS", os);
        } else {
            assertEquals(actualEnvOs, os);
        }

        // Validate JRE Description
        String jre = metadata.get("jre_description");
        assertNotNull(jre, "JRE description must not be null");
        assertFalse(jre.isBlank(), "JRE description should not be blank");

        String actualEnvJre = Environment.getJreDescription();
        if (actualEnvJre == null || actualEnvJre.isBlank()) {
            assertEquals("unknown JRE", jre);
        } else {
            assertEquals(actualEnvJre, jre);
        }
    }
}
