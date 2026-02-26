package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainProjectAnalyzerLanguagesPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void testSetAnalyzerLanguagesPersistsInternalNamesSorted() throws IOException {
        MainProject project = MainProject.forTests(tempDir);

        // Use languages where internal name and name differ (C# -> C_SHARP)
        // and ensure they are sorted (PYTHON starts with P, C_SHARP with C)
        Set<Language> langs = Set.of(Languages.PYTHON, Languages.C_SHARP);
        project.setAnalyzerLanguages(langs);

        // Verify the properties file content
        Path propsPath = tempDir.resolve(".brokk").resolve("project.properties");
        assertTrue(Files.exists(propsPath), "Properties file should exist");

        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(propsPath)) {
            props.load(reader);
        }

        String persisted = props.getProperty("code_intelligence_languages");
        // Should be sorted alphabetically by internal name: C_SHARP, PYTHON
        assertEquals("C_SHARP,PYTHON", persisted);

        // Verify restoration
        MainProject reloadedProject = new MainProject(tempDir);
        Set<Language> restoredLangs = reloadedProject.getAnalyzerLanguages();

        assertEquals(2, restoredLangs.size(), "Should have restored 2 languages");
        assertTrue(restoredLangs.contains(Languages.C_SHARP), "Should contain C_SHARP");
        assertTrue(restoredLangs.contains(Languages.PYTHON), "Should contain PYTHON");
    }
}
