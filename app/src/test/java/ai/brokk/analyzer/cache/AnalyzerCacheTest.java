package ai.brokk.analyzer.cache;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterJava;

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

    @Test
    void testTransferConstructor(@TempDir Path tempDir) {
        // 1. Setup files and CodeUnits
        ProjectFile pfUnchanged = new ProjectFile(tempDir, "Unchanged.java");
        ProjectFile pfChanged = new ProjectFile(tempDir, "Changed.java");
        ProjectFile pfAliasToChanged = pfChanged;

        CodeUnit cuUnchanged = CodeUnit.cls(pfUnchanged, "com.test", "Unchanged");
        CodeUnit cuChanged = CodeUnit.cls(pfChanged, "com.test", "Changed");
        CodeUnit cuAlsoFromChanged = CodeUnit.cls(pfAliasToChanged, "com.test", "AlsoChanged");

        // 2. Create real TSTree objects
        TSParser parser = new TSParser();
        parser.setLanguage(new TreeSitterJava());
        TSTree treeUnchanged = parser.parseString(null, "class Unchanged {}\n");
        TSTree treeChanged = parser.parseString(null, "class Changed {}\n");

        // 3. Populate previous cache
        AnalyzerCache previous = new AnalyzerCache();

        // trees
        previous.trees().put(pfUnchanged, treeUnchanged);
        previous.trees().put(pfChanged, treeChanged);

        // rawSupertypes
        previous.rawSupertypes().put(cuUnchanged, List.of("BaseU"));
        previous.rawSupertypes().put(cuChanged, List.of("BaseC"));
        previous.rawSupertypes().put(cuAlsoFromChanged, List.of("BaseAlsoC"));

        // imports
        previous.imports().computeForwardIfAbsent(pfUnchanged, k -> Set.of(cuUnchanged));
        previous.imports().computeForwardIfAbsent(pfChanged, k -> Set.of(cuChanged));

        // typeHierarchy
        previous.typeHierarchy().computeForwardIfAbsent(cuUnchanged, k -> List.of(cuUnchanged));
        previous.typeHierarchy().computeForwardIfAbsent(cuChanged, k -> List.of(cuChanged));
        previous.typeHierarchy().computeForwardIfAbsent(cuAlsoFromChanged, k -> List.of(cuAlsoFromChanged));

        // 4. Create next cache via transfer constructor
        AnalyzerCache next = new AnalyzerCache(previous, Set.of(pfAliasToChanged));

        // 5. Assertions
        // (a) Unchanged entries are preserved
        assertSame(treeUnchanged, next.trees().get(pfUnchanged), "Unchanged tree should be preserved");
        assertEquals(
                List.of("BaseU"), next.rawSupertypes().get(cuUnchanged), "Unchanged rawSupertypes should be preserved");
        assertEquals(
                Set.of(cuUnchanged), next.imports().getForward(pfUnchanged), "Unchanged imports should be preserved");
        assertEquals(
                List.of(cuUnchanged),
                next.typeHierarchy().getForward(cuUnchanged),
                "Unchanged typeHierarchy should be preserved");

        // (b) Changed entries are excluded
        assertNull(next.trees().get(pfChanged), "Changed tree should be excluded");
        assertNull(next.rawSupertypes().get(cuChanged), "Changed rawSupertypes should be excluded");
        assertNull(next.imports().getForward(pfChanged), "Changed imports should be excluded");
        assertNull(next.typeHierarchy().getForward(cuChanged), "Changed typeHierarchy should be excluded");

        assertNull(next.rawSupertypes().get(cuAlsoFromChanged), "AlsoChanged rawSupertypes should be excluded");
        assertNull(next.typeHierarchy().getForward(cuAlsoFromChanged), "AlsoChanged typeHierarchy should be excluded");

        // (c) Specifically validate CodeUnit filtering uses cu.source()
        // cuChanged entries were removed because cuChanged.source() == pfChanged which is in changedFiles.
        // cuUnchanged survived because cuUnchanged.source() == pfUnchanged which is NOT in changedFiles.
    }
}
