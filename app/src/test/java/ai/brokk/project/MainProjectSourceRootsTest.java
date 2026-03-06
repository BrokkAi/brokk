package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ai.brokk.analyzer.Languages;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainProjectSourceRootsTest {

    @TempDir
    Path tempDir;

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
