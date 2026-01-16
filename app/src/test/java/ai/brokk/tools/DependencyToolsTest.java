package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import ai.brokk.util.MavenArtifactFetcher;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DependencyToolsTest {

    @TempDir
    Path tempDir;

    @Test
    void importMavenDependency_InvalidCoordinates_ReturnsErrorMessage() throws InterruptedException {
        var cm = new TestContextManager(new TestProject(tempDir));
        var tools = new DependencyTools(cm);

        String[] invalidInputs = {"", "justgroup", "too:many:parts:here:extra", "  "};

        for (String input : invalidInputs) {
            String result = tools.importMavenDependency(input);
            assertTrue(result.contains("Invalid coordinates format"), "Should fail for: " + input);
        }
    }

    @Test
    void importMavenDependency_ThreeParts_SkipsVersionResolution() throws InterruptedException {
        var cm = new TestContextManager(new TestProject(tempDir));
        var recordedCoords = new AtomicReference<String>();
        var mockFetcher = new MavenArtifactFetcher() {
            @Override
            public Optional<String> resolveLatestVersion(String groupId, String artifactId) {
                throw new AssertionError("Should not resolve version when provided");
            }

            @Override
            public Optional<Path> fetch(String coordinates, String classifier) {
                recordedCoords.set(coordinates);
                // Return empty to stop execution after coordinate check
                return Optional.empty();
            }
        };

        var tools = new DependencyTools(cm, mockFetcher);
        String result = tools.importMavenDependency("org.example:lib:1.2.3");

        assertEquals("org.example:lib:1.2.3", recordedCoords.get());
        String expectedMessage =
                "Could not find artifact org.example:lib:1.2.3 on Maven Central. Check the coordinates and try again.";
        assertEquals(expectedMessage, result);
    }

    @Test
    void importMavenDependency_TwoParts_TriggersVersionResolution() throws InterruptedException {
        var cm = new TestContextManager(new TestProject(tempDir));
        var recordedCoords = new AtomicReference<String>();
        var resolveCount = new AtomicInteger(0);
        var mockFetcher = new MavenArtifactFetcher() {
            @Override
            public Optional<String> resolveLatestVersion(String groupId, String artifactId) {
                resolveCount.incrementAndGet();
                if ("org.example".equals(groupId) && "lib".equals(artifactId)) {
                    return Optional.of("2.0.0");
                }
                return Optional.empty();
            }

            @Override
            public Optional<Path> fetch(String coordinates, String classifier) {
                recordedCoords.set(coordinates);
                return Optional.empty();
            }
        };

        var tools = new DependencyTools(cm, mockFetcher);
        String result = tools.importMavenDependency("org.example:lib");

        assertEquals(1, resolveCount.get(), "Should resolve version exactly once");
        assertEquals("org.example:lib:2.0.0", recordedCoords.get());
        String expectedMessage =
                "Could not find artifact org.example:lib:2.0.0 on Maven Central. Check the coordinates and try again.";
        assertEquals(expectedMessage, result);
    }

    @Test
    void importMavenDependency_VersionResolutionFails_ReturnsErrorMessage() throws InterruptedException {
        var cm = new TestContextManager(new TestProject(tempDir));
        var mockFetcher = new MavenArtifactFetcher() {
            @Override
            public Optional<String> resolveLatestVersion(String groupId, String artifactId) {
                return Optional.empty();
            }
        };

        var tools = new DependencyTools(cm, mockFetcher);
        String result = tools.importMavenDependency("org.example:unknown");

        assertTrue(result.contains("Could not resolve latest version"), "Should report resolution failure");
    }

    /**
     * Integration test that actually downloads and decompiles a real library.
     * Uses slf4j-api as it's small and stable.
     */
    @Test
    void importMavenDependency_RealLibrary_DownloadsAndDecompiles() throws Exception {
        var cm = new TestContextManager(new TestProject(tempDir));
        var tools = new DependencyTools(cm);

        // Import a small, well-known library with explicit version
        String result = tools.importMavenDependency("org.slf4j:slf4j-api:2.0.9");

        // Verify success
        assertTrue(result.contains("Successfully imported"), "Expected success, got: " + result);
        assertTrue(result.contains("slf4j-api"), "Should mention artifact name");
        assertTrue(result.contains(".brokk/dependencies"), "Should mention output location");

        // Verify files were extracted
        var depsDir = tempDir.resolve(".brokk/dependencies");
        assertTrue(java.nio.file.Files.exists(depsDir), "Dependencies dir should exist");

        try (var dirs = java.nio.file.Files.list(depsDir)) {
            var artifactDir = dirs.filter(p -> p.getFileName().toString().contains("slf4j"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No slf4j directory found"));

            // Count Java files
            try (var walk = java.nio.file.Files.walk(artifactDir)) {
                long javaFiles =
                        walk.filter(p -> p.toString().endsWith(".java")).count();
                assertTrue(javaFiles > 0, "Should have extracted Java files, found: " + javaFiles);
            }
        }
    }
}
