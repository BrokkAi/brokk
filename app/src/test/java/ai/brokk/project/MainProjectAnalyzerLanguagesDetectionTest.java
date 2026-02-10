package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class MainProjectAnalyzerLanguagesDetectionTest {

    @TempDir
    Path tempDir;

    @Test
    public void testAutoDetectsLanguagesFromFilesystem() throws IOException {
        // Setup: Create a non-git project with Java and Python files
        Files.writeString(tempDir.resolve("A.java"), "public class A {}");
        Files.writeString(tempDir.resolve("b.py"), "def b(): pass");
        Files.writeString(tempDir.resolve("README.md"), "# Project");

        // Instantiate MainProject (uses LocalFileRepo because there's no .git)
        try (MainProject project = MainProject.forTests(tempDir)) {
            Set<Language> languages = project.getAnalyzerLanguages();

            // Assert: Detected Java and Python
            assertEquals(2, languages.size(), "Should detect exactly 2 languages");
            assertTrue(languages.contains(Languages.JAVA), "Should contain JAVA");
            assertTrue(languages.contains(Languages.PYTHON), "Should contain PYTHON");
        }
    }

    @Test
    public void testReturnsNoneWhenNoRecognizedLanguagesFound() throws IOException {
        // Setup: Create files with unrecognized extensions
        Files.writeString(tempDir.resolve("README.md"), "# Project");
        Files.writeString(tempDir.resolve("data.json"), "{}");
        Files.writeString(tempDir.resolve("script.sh"), "echo hello");

        try (MainProject project = MainProject.forTests(tempDir)) {
            Set<Language> languages = project.getAnalyzerLanguages();

            // Assert: Returns Languages.NONE
            assertEquals(Set.of(Languages.NONE), languages);
        }
    }

    // ============== New tests for dependency-based detection ==============

    @Test
    public void testDetectsLanguagesFromLiveDependenciesOnly() throws IOException {
        // No recognizable files in the main repo
        Files.writeString(tempDir.resolve("README.md"), "# Project");

        // Create a Java dependency under .brokk/dependencies/dep-java
        Path depDir = createJavaDependency(tempDir, "dep-java");

        try (MainProject project = MainProject.forTests(tempDir)) {
            // Mark dependency as live
            project.saveLiveDependencies(Set.of(depDir));

            // Act
            Set<Language> languages = project.getAnalyzerLanguages();

            // Assert: Should detect Java from dependency and nothing else
            assertEquals(1, languages.size(), "Should detect exactly 1 language from dependencies");
            assertTrue(languages.contains(Languages.JAVA), "Should contain JAVA detected from dependency");
            assertTrue(!languages.contains(Languages.NONE), "Should not contain NONE when a real language is detected");
        }
    }

    @Test
    public void testExplicitAnalyzerLanguagesOverrideIgnoresDependencies() throws IOException {
        // Create a Java dependency under .brokk/dependencies/dep-java
        Path depDir = createJavaDependency(tempDir, "dep-java");

        try (MainProject project = MainProject.forTests(tempDir)) {
            // Mark dependency as live
            project.saveLiveDependencies(Set.of(depDir));

            // Explicitly override analyzer languages to GO
            project.setAnalyzerLanguages(Set.of(Languages.GO));

            // Act
            Set<Language> languages = project.getAnalyzerLanguages();

            // Assert: Explicit override wins; only GO is returned
            assertEquals(Set.of(Languages.GO), languages);
            assertTrue(!languages.contains(Languages.JAVA), "Override should ignore dependency-detected JAVA");
            assertTrue(!languages.contains(Languages.NONE), "Override should not result in NONE");
        }
    }

    /**
     * Helper to create a dependency directory under .brokk/dependencies/<depName> with a simple
     * Java source file. Returns the top-level dependency directory Path suitable for passing to
     * saveLiveDependencies().
     */
    private Path createJavaDependency(Path projectRoot, String depName) throws IOException {
        Path dependenciesDir = projectRoot.resolve(AbstractProject.BROKK_DIR).resolve(AbstractProject.DEPENDENCIES_DIR);
        Files.createDirectories(dependenciesDir);

        Path depDir = dependenciesDir.resolve(depName);
        Files.createDirectories(depDir);

        Path sourceFile = depDir.resolve("Main.java");
        Files.writeString(sourceFile, "public class Main {}");
        return depDir;
    }
}
