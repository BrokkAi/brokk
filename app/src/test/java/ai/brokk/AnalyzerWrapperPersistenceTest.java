package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.TreeSitterStateIO;
import ai.brokk.testutil.TestProject;
import ai.brokk.watchservice.NoopWatchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalyzerWrapperPersistenceTest {

    @TempDir
    Path tempDir;

    private static void writeAnalyzerStateDto(Path out, String schemaVersion, String languageInternalName)
            throws Exception {
        var dto = new TreeSitterStateIO.AnalyzerStateDto(
                Map.of(), List.of(), List.of(), List.of(), null, 1L, schemaVersion, languageInternalName);
        var mapper = new ObjectMapper(new SmileFactory());
        try (var os = Files.newOutputStream(out);
                var lz4 = new LZ4FrameOutputStream(os)) {
            mapper.writeValue(lz4, dto);
        }
    }

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
            wrapper.getReadyAndPersisted();
            project.setAnalyzerLanguages(Set.of(Languages.JAVA));
            // This should not throw any exceptions even if files don't exist
            wrapper.deletePersistedAnalyzerStateFiles();
        }
    }

    @Test
    void testGetReadyAndPersistedWritesFreshAnalyzerState() throws Exception {
        Language lang = Languages.JAVA;
        Files.writeString(tempDir.resolve("A.java"), "public class A {}");

        TestProject project = new TestProject(tempDir, lang);
        project.setAnalyzerLanguages(Set.of(lang));

        Path storage = lang.getStoragePath(project);
        assertFalse(Files.exists(storage), "Analyzer state should not exist before build");

        try (NoopWatchService stubWatchService = new NoopWatchService();
                AnalyzerWrapper wrapper = new AnalyzerWrapper(project, new NullAnalyzerListener(), stubWatchService)) {
            wrapper.getReadyAndPersisted();
            assertTrue(Files.exists(storage), "Fresh analyzer build should be persisted");
        }
    }

    @Test
    void testGetReadyAndPersistedOverwritesRejectedAnalyzerState() throws Exception {
        Language lang = Languages.JAVA;
        Files.writeString(tempDir.resolve("A.java"), "public class A {}");

        TestProject project = new TestProject(tempDir, lang);
        project.setAnalyzerLanguages(Set.of(lang));

        Path storage = lang.getStoragePath(project);
        Files.createDirectories(storage.getParent());
        writeAnalyzerStateDto(storage, "2.2.0", "JAVA");

        assertThrows(
                TreeSitterStateIO.RejectedAnalyzerStateException.class,
                () -> TreeSitterStateIO.load(storage),
                "Precondition: incompatible analyzer state should be rejected");

        try (NoopWatchService stubWatchService = new NoopWatchService();
                AnalyzerWrapper wrapper = new AnalyzerWrapper(project, new NullAnalyzerListener(), stubWatchService)) {
            wrapper.getReadyAndPersisted();
        }

        var persisted = TreeSitterStateIO.load(storage);
        var persistedDto = TreeSitterStateIO.toDto(persisted, lang);
        assertEquals("3.0.0", persistedDto.schemaVersion(), "Rejected analyzer state should be rewritten");
        assertFalse(persistedDto.codeUnitState().isEmpty(), "Rewritten analyzer state should contain project symbols");
    }
}
