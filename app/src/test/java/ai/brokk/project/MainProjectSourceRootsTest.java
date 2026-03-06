package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ai.brokk.analyzer.Languages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainProjectSourceRootsTest {

    @TempDir
    Path tempDir;

    @Test
    void testLegacyJavaKeyFallback() throws IOException {
        Path brokkDir = tempDir.resolve(".brokk");
        Files.createDirectories(brokkDir);

        Properties props = new Properties();
        props.setProperty("javaSourceRoots", "[\"legacy/src\"]");
        try (var writer = Files.newBufferedWriter(brokkDir.resolve("project.properties"))) {
            props.store(writer, null);
        }

        MainProject project = MainProject.forTests(tempDir);
        List<String> roots = project.getSourceRoots(Languages.JAVA);

        assertEquals(List.of("legacy/src"), roots);
    }

    @Test
    void testJavaMigrationOnWrite() throws IOException {
        Path brokkDir = tempDir.resolve(".brokk");
        Files.createDirectories(brokkDir);

        Properties props = new Properties();
        props.setProperty("javaSourceRoots", "[\"legacy/src\"]");
        Path propsFile = brokkDir.resolve("project.properties");
        try (var writer = Files.newBufferedWriter(propsFile)) {
            props.store(writer, null);
        }

        MainProject project = MainProject.forTests(tempDir);
        project.setSourceRoots(Languages.JAVA, List.of("new/src"));

        // Verify state in memory
        assertEquals(List.of("new/src"), project.getSourceRoots(Languages.JAVA));

        // Verify state on disk
        Properties updatedProps = new Properties();
        try (var reader = Files.newBufferedReader(propsFile)) {
            updatedProps.load(reader);
        }

        assertEquals("[\"new/src\"]", updatedProps.getProperty("JAVASourceRoots"));
        assertFalse(updatedProps.containsKey("javaSourceRoots"), "Legacy key should be removed after migration");
    }

    @Test
    void testPerLanguagePersistenceNoInterference() {
        MainProject project = MainProject.forTests(tempDir);

        project.setSourceRoots(Languages.JAVA, List.of("java/src"));
        project.setSourceRoots(Languages.PYTHON, List.of("python/src"));

        assertEquals(List.of("java/src"), project.getSourceRoots(Languages.JAVA));
        assertEquals(List.of("python/src"), project.getSourceRoots(Languages.PYTHON));

        // Ensure default fallback (SourceRootScanner) still works for unconfigured languages
        // We can't easily assert exactly what scanner finds, but we can verify it doesn't return the python/java roots.
        List<String> goRoots = project.getSourceRoots(Languages.GO);
        assertFalse(goRoots.contains("java/src"));
        assertFalse(goRoots.contains("python/src"));
    }
}
