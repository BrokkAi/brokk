package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        Path dep1 = root.resolve(".brokk/dependencies/dep1");
        Path dep2 = root.resolve(".brokk/dependencies/dep2");
        Files.createDirectories(dep1);
        Files.createDirectories(dep2);

        // Use MainProject.forTests to avoid sealed class subclassing issues
        MainProject project = MainProject.forTests(root);

        // Test resolving via namesToDependencies helper
        // namesToDependencies calls getAllOnDiskDependencies() which scans .brokk/dependencies
        Set<IProject.Dependency> resolved = project.namesToDependencies("dep1, dep2");

        assertEquals(2, resolved.size());
        Set<String> names = resolved.stream()
                .map(d -> d.root().getRelPath().getFileName().toString())
                .collect(Collectors.toSet());

        assertTrue(names.contains("dep1"));
        assertTrue(names.contains("dep2"));
    }
}
