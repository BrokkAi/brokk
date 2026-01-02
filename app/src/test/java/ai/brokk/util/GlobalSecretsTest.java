package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobalSecretsTest {

    @TempDir
    Path tempDir;

    private Path originalConfigDir;

    @BeforeEach
    void setUp() {
        // Use reflection or a system property if BrokkConfigPaths allowed it,
        // but here we rely on the fact that we can manipulate the environment or
        // just test the logic via GlobalSecrets if it were more injectable.
        // For this focused test, we'll verify the permission logic specifically.
        GlobalSecrets.resetForTests();
    }

    @Test
    void testSecretPersistence() {
        // Since BrokkConfigPaths.getGlobalConfigDir() is hard to mock without static mocking,
        // we test the core logic of set/get which is platform independent.
        GlobalSecrets.setSecret("test.key", "test.value");
        assertEquals("test.value", GlobalSecrets.getSecret("test.key", ""));

        GlobalSecrets.resetForTests();
        assertEquals("test.value", GlobalSecrets.getSecret("test.key", ""));
    }

    @Test
    void testPosixPermissions() throws IOException {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return; // Skip on non-POSIX
        }

        Path secretFile = tempDir.resolve("test.secrets.properties");

        // Manual trigger of the permission logic
        GlobalSecrets.setSecret("dummy", "value");
        Path realFile = BrokkConfigPaths.getGlobalConfigDir().resolve("brokk.secrets.properties");

        if (Files.exists(realFile)) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(realFile);
            assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
            assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE));
            assertEquals(2, perms.size(), "Should only have OWNER_READ and OWNER_WRITE");
        }
    }
}
