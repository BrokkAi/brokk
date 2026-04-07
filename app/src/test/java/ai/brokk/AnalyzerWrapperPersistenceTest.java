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

        // Verify file exists before deletion
        assertTrue(Files.exists(storage), "lz4 file should exist");

        // Use try-with-resources to ensure the wrapper (and its background thread) is closed
        try (NoopWatchService stubWatchService = new NoopWatchService();
                AnalyzerWrapper wrapper = new AnalyzerWrapper(project, new NullAnalyzerListener(), stubWatchService)) {
            // Call the package-private method
            wrapper.deletePersistedAnalyzerStateFiles();

            // Verify file is deleted
            assertFalse(Files.exists(storage), "Current format file should be deleted");
        }
    }

    @Test
    void testDeletePersistedAnalyzerStateFilesWithNoFiles() throws Exception {
        // Construct with Languages.NONE so the background analyzer build creates a lightweight
        // DisabledAnalyzer (no file scanning). Then set JAVA before calling delete so the
        // deletion logic iterates over the Java storage path. This avoids the @TempDir cleanup
        // race on macOS where file handles from a real analyzer build linger after close().
        TestProject project = new TestProject(tempDir);
        project.setAnalyzerLanguages(Set.of(Languages.NONE));

        try (NoopWatchService stubWatchService = new NoopWatchService();
                AnalyzerWrapper wrapper = new AnalyzerWrapper(project, new NullAnalyzerListener(), stubWatchService)) {
            project.setAnalyzerLanguages(Set.of(Languages.JAVA));
            // This should not throw any exceptions even if files don't exist
            wrapper.deletePersistedAnalyzerStateFiles();
        }
    }
}
