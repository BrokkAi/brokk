package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyResolutionTest {

    @Test
    void testNamesToDependencies(@TempDir Path root) throws IOException {
        // Ensure dependencies directories exist in the standard .brokk/dependencies location
        Path dep1Dir = root.resolve(".brokk/dependencies/dep1");
        Path dep2Dir = root.resolve(".brokk/dependencies/dep2");
        Files.createDirectories(dep1Dir);
        Files.createDirectories(dep2Dir);

        // Create a test project instance
        Path dependenciesPath = root.resolve(".brokk/dependencies");
        Set<ProjectFile> allOnDiskDependencies = Set.of();
        if (Files.exists(dependenciesPath)) {
            try (var stream = Files.list(dependenciesPath)) {
                allOnDiskDependencies = stream.filter(Files::isDirectory)
                        .map(p -> new ProjectFile(root, root.relativize(p)))
                        .collect(Collectors.toSet());
            } catch (IOException e) {
                allOnDiskDependencies = Set.of();
            }
        }
        IProject project = new TestProject(root).withDependencies(allOnDiskDependencies, Set.of());

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
