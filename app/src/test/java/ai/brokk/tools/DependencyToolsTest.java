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
    void importMavenDependency_InvalidCoordinates_ReturnsErrorMessage() {
        var cm = new TestContextManager(new TestProject(tempDir));
        var tools = new DependencyTools(cm);

        String[] invalidInputs = {
            "",
            "justgroup",
            "too:many:parts:here:extra",
            "  "
        };

        for (String input : invalidInputs) {
            String result = tools.importMavenDependency(input);
            assertTrue(result.contains("Invalid coordinates format"), "Should fail for: " + input);
        }
    }

    @Test
    void importMavenDependency_ThreeParts_SkipsVersionResolution() {
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
        String expectedMessage = "Could not find artifact org.example:lib:1.2.3 on Maven Central. Check the coordinates and try again.";
        assertEquals(expectedMessage, result);
    }

    @Test
    void importMavenDependency_TwoParts_TriggersVersionResolution() {
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
        String expectedMessage = "Could not find artifact org.example:lib:2.0.0 on Maven Central. Check the coordinates and try again.";
        assertEquals(expectedMessage, result);
    }

    @Test
    void importMavenDependency_VersionResolutionFails_ReturnsErrorMessage() {
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
}
