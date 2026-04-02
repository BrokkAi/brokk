package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.testutil.TestProject;
import ai.brokk.watchservice.NoopWatchService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalyzerWrapperPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void testDeletePersistedAnalyzerStateFiles() throws Exception {
        Language lang = Languages.JAVA;
        // Instantiate TestProject with the specific language so getLanguageHandle returns it correctly
        TestProject project = new TestProject(tempDir, lang);
        project.setAnalyzerLanguages(Set.of(lang));

        Path storage = lang.getStoragePath(project);
        Files.createDirectories(storage.getParent());

        // Create current format file (.bin.lz4)
        Files.writeString(storage, "dummy lz4");

        // Create legacy format files (.bin.gz and .bin.gzip)
        String name = storage.getFileName().toString();
        // storage is e.g. "java.bin.lz4", base is "java.bin"
        String base = name.endsWith(".lz4") ? name.substring(0, name.length() - 4) : name;
        Path parent = storage.getParent();

        Path legacyGz = parent.resolve(base + ".gz");
        Path legacyGzip = parent.resolve(base + ".gzip");

        Files.writeString(legacyGz, "dummy gz");
        Files.writeString(legacyGzip, "dummy gzip");

        // Verify files exist before deletion
        assertTrue(Files.exists(storage), "lz4 file should exist");
        assertTrue(Files.exists(legacyGz), "gz file should exist");
        assertTrue(Files.exists(legacyGzip), "gzip file should exist");

        // Use try-with-resources to ensure the wrapper (and its background thread) is closed
        try (NoopWatchService stubWatchService = new NoopWatchService();
                AnalyzerWrapper wrapper = new AnalyzerWrapper(project, new NullAnalyzerListener(), stubWatchService)) {
            // Call the package-private method
            wrapper.deletePersistedAnalyzerStateFiles();

            // Verify all files are deleted
            assertFalse(Files.exists(storage), "Current format file should be deleted");
            assertFalse(Files.exists(legacyGz), "Legacy .gz file should be deleted");
            assertFalse(Files.exists(legacyGzip), "Legacy .gzip file should be deleted");
        }
    }

    @Test
    void testDeletePersistedAnalyzerStateFilesWithNoFiles() throws Exception {
        TestProject project = new TestProject(tempDir);
        project.setAnalyzerLanguages(Set.of(Languages.JAVA));

        try (NoopWatchService stubWatchService = new NoopWatchService();
                AnalyzerWrapper wrapper = new AnalyzerWrapper(project, new NullAnalyzerListener(), stubWatchService)) {
            // This should not throw any exceptions even if files don't exist
            wrapper.deletePersistedAnalyzerStateFiles();
        }
    }
}
