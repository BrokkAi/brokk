package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LocalCacheScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void findLatestVersion_MavenRepoStructure_ReturnsHighestVersion() throws Exception {
        // Set up a fake Maven local repo structure
        var m2Repo = tempDir.resolve(".m2/repository");
        var artifactDir = m2Repo.resolve("com/example/mylib");

        // Create version directories with JARs
        createVersionWithJar(artifactDir, "mylib", "1.0.0");
        createVersionWithJar(artifactDir, "mylib", "1.2.0");
        createVersionWithJar(artifactDir, "mylib", "1.10.0");
        createVersionWithJar(artifactDir, "mylib", "2.0.0-beta");

        // Override user.home temporarily
        var originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());

            var result = LocalCacheScanner.findLatestVersion("com.example", "mylib");

            assertTrue(result.isPresent(), "Should find versions");
            assertEquals("2.0.0-beta", result.get(), "Should return highest version");
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void findLatestVersion_SemanticVersionOrdering_HandlesComplexVersions() throws Exception {
        var m2Repo = tempDir.resolve(".m2/repository");
        var artifactDir = m2Repo.resolve("org/test/lib");

        // Create versions that require proper semantic comparison
        createVersionWithJar(artifactDir, "lib", "1.9.0");
        createVersionWithJar(artifactDir, "lib", "1.10.0");
        createVersionWithJar(artifactDir, "lib", "1.10.1");

        var originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());

            var result = LocalCacheScanner.findLatestVersion("org.test", "lib");

            assertTrue(result.isPresent());
            assertEquals("1.10.1", result.get(), "1.10.1 > 1.10.0 > 1.9.0");
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void findLatestVersion_NoVersionsFound_ReturnsEmpty() throws Exception {
        var originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());

            var result = LocalCacheScanner.findLatestVersion("nonexistent", "artifact");

            assertTrue(result.isEmpty(), "Should return empty for missing artifact");
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void findArtifact_ExistsInMavenRepo_ReturnsPath() throws Exception {
        var m2Repo = tempDir.resolve(".m2/repository");
        var jarPath = createVersionWithJar(m2Repo.resolve("com/example/mylib"), "mylib", "1.0.0");

        var originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());

            var result = LocalCacheScanner.findArtifact("com.example", "mylib", "1.0.0");

            assertTrue(result.isPresent(), "Should find artifact");
            assertEquals(jarPath, result.get());
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void findArtifact_DoesNotExist_ReturnsEmpty() throws Exception {
        var originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());

            var result = LocalCacheScanner.findArtifact("com.example", "missing", "1.0.0");

            assertTrue(result.isEmpty());
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    private Path createVersionWithJar(Path artifactDir, String artifactId, String version) throws Exception {
        var versionDir = artifactDir.resolve(version);
        Files.createDirectories(versionDir);
        var jarPath = versionDir.resolve(artifactId + "-" + version + ".jar");
        Files.writeString(jarPath, "dummy jar content");
        return jarPath;
    }
}
