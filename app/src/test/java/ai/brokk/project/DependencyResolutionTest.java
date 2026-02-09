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

    private static final class TestProject extends AbstractProject {
        private final Set<ProjectFile> mockDeps;

        TestProject(Path root, Set<ProjectFile> mockDeps) {
            super(root);
            this.mockDeps = mockDeps;
        }

        @Override
        public Set<Dependency> getLiveDependencies() {
            return Set.of();
        }

        @Override
        public void saveLiveDependencies(Set<Path> dependencyTopLevelDirs) {}

        @Override
        public Set<ProjectFile> getAllOnDiskDependencies() {
            return mockDeps;
        }
    }

    @Test
    void testNamesToDependenciesWithVaryingDepths(@TempDir Path root) throws IOException {
        // Ensure dependencies directories exist so ProjectFile validation (if any) or root resolution works
        Files.createDirectories(root.resolve(".brokk/dependencies/dep1"));
        Files.createDirectories(root.resolve("custom/path/to/dependencies/dep2"));

        Set<ProjectFile> mockDeps = Set.of(
                new ProjectFile(root, Path.of(".brokk/dependencies/dep1")),
                new ProjectFile(root, Path.of("custom/path/to/dependencies/dep2")));

        // Create a mock AbstractProject that allows us to test namesToDependencies
        AbstractProject project = new TestProject(root, mockDeps);

        // Test resolving both
        Set<IProject.Dependency> resolved = project.namesToDependencies("dep1, dep2");

        assertEquals(2, resolved.size());
        Set<String> names = resolved.stream()
                .map(d -> d.root().getRelPath().getFileName().toString())
                .collect(Collectors.toSet());

        assertTrue(names.contains("dep1"));
        assertTrue(names.contains("dep2"));
    }
}
