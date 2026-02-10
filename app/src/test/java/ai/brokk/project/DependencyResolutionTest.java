package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyResolutionTest {

    /**
     * Minimal implementation of IProject for testing default methods that require on-disk dependency discovery.
     */
    private record ResolutionTestProject(Path root) implements IProject {
        @Override
        public Path getRoot() {
            return root;
        }

        @Override
        public Path getMasterRootPathForConfig() {
            return root;
        }

        @Override
        public Set<ProjectFile> getAllOnDiskDependencies() {
            Path dependenciesPath = root.resolve(".brokk/dependencies");
            if (!Files.exists(dependenciesPath)) {
                return Set.of();
            }
            try (var stream = Files.list(dependenciesPath)) {
                return stream.filter(Files::isDirectory)
                        .map(p -> new ProjectFile(root, root.relativize(p)))
                        .collect(Collectors.toSet());
            } catch (IOException e) {
                return Set.of();
            }
        }
    }

    @Test
    void testNamesToDependencies(@TempDir Path root) throws IOException {
        // Ensure dependencies directories exist in the standard .brokk/dependencies location
        Path dep1Dir = root.resolve(".brokk/dependencies/dep1");
        Path dep2Dir = root.resolve(".brokk/dependencies/dep2");
        Files.createDirectories(dep1Dir);
        Files.createDirectories(dep2Dir);

        // Create a test project instance
        IProject project = new ResolutionTestProject(root);

        // Act: resolve from string
        Set<IProject.Dependency> resolved = project.resolveDependencies("dep1, dep2");

        // Assert
        assertEquals(2, resolved.size());
        Set<String> names = resolved.stream()
                .map(d -> d.root().getRelPath().getFileName().toString())
                .collect(Collectors.toSet());

        assertTrue(names.contains("dep1"));
        assertTrue(names.contains("dep2"));
    }
}
