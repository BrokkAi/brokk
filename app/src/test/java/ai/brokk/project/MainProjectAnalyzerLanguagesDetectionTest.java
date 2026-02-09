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
}
