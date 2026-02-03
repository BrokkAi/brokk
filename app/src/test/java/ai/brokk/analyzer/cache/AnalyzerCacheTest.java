package ai.brokk.analyzer.cache;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalyzerCacheTest {

    @Test
    void testIsEmpty(@TempDir Path tempDir) {
        AnalyzerCache cache = new AnalyzerCache();
        assertTrue(cache.isEmpty(), "Cache should be empty initially");

        ProjectFile pf = new ProjectFile(tempDir, "Test.java");
        CodeUnit cu = CodeUnit.cls(pf, "com.test", "Test");

        // Reset and check rawSupertypes
        cache = new AnalyzerCache();
        cache.rawSupertypes().put(cu, List.of("Base"));
        assertFalse(cache.isEmpty(), "Cache should not be empty after adding rawSupertypes");

        // Reset and check imports
        cache = new AnalyzerCache();
        cache.imports().computeForwardIfAbsent(pf, k -> Set.of(cu));
        assertFalse(cache.isEmpty());

        // Reset and check typeHierarchy
        cache = new AnalyzerCache();
        cache.typeHierarchy().computeForwardIfAbsent(cu, k -> List.of(cu));
        assertFalse(cache.isEmpty());
    }

    @Test
    void testSnapshot() {
        AnalyzerCache cache = new AnalyzerCache();
        AnalyzerCache.CacheSnapshot snapshot = cache.snapshot();

        assertNotNull(snapshot);
        assertSame(cache.trees(), snapshot.trees());
        assertSame(cache.rawSupertypes(), snapshot.rawSupertypes());
        assertSame(cache.imports(), snapshot.imports());
        assertSame(cache.typeHierarchy(), snapshot.typeHierarchy());
    }
}
