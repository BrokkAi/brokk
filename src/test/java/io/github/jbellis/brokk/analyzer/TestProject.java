package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.git.IGitRepo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lightweight IProject implementation for unit-testing Tree-sitter analyzers.
 */
final class TestProject implements IProject {
    private final Path root;
    private final Language language; // Use Brokk's Language enum

    TestProject(Path root, Language language) {
        this.root = root;
        this.language = language;
    }

    /** Creates a TestProject rooted under src/test/resources/{subDir}. */
    static TestProject createTestProject(String subDir, Language lang) { // Use Brokk's Language enum
        Path testDir = Path.of("src/test/resources", subDir);
        assertTrue(Files.exists(testDir), "Test resource dir missing: " + testDir);
        assertTrue(Files.isDirectory(testDir), testDir + " is not a directory");
        return new TestProject(testDir.toAbsolutePath(), lang);
    }

    @Override
    public Set<Language> getAnalyzerLanguages() {
        return Set.of(language);
    }

    @Override
    public Path getRoot() {
        return root;
    }

    @Override
    public IGitRepo getRepo() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<ProjectFile> getAllFiles() {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> new ProjectFile(root, root.relativize(p)))
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            System.err.printf("ERROR (TestProject.getAllFiles): walk failed on %s: %s%n",
                              root, e.getMessage());
            e.printStackTrace(System.err);
            return Collections.emptySet();
        }
    }
}
