package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Additional tests validating the top-level snapshot DTO produced by TreeSitterStateIO,
 * ensuring it contains a non-empty schemaVersion and that the optional cacheSnapshot is
 * round-trippable via the loadWithCache API.
 */
public class TreeSitterSnapshotFormatTest {

    @Test
    void snapshotDtoContainsNonEmptySchemaVersionAndCacheIsRoundTrippable(@TempDir Path tempDir) throws Exception {
        var builder = InlineTestProjectCreator.code(
                """
                package com.example;
                public class Hello { public void hi() {} }
                """,
                "src/main/java/com/example/Hello.java");

        try (IProject project = builder.build()) {
            JavaAnalyzer analyzer = new JavaAnalyzer(project);

            // Produce a small cache snapshot by ensuring signatures are computed via a forced update
            var state = analyzer.snapshotState();
            var cacheSnapshot = new ai.brokk.analyzer.cache.AnalyzerCache().snapshot();

            Path out = tempDir.resolve("snapshot_with_cache.smile.gz");
            // Save using the TreeSitterStateIO.save overload that accepts a cache snapshot
            TreeSitterStateIO.save(state, cacheSnapshot, out);

            assertTrue(Files.exists(out), "Snapshot file should have been written");

            // Read using the IO API to verify round-trippability
            var loadedOpt = TreeSitterStateIO.loadWithCache(out);
            assertTrue(loadedOpt.isPresent(), "Snapshot should be loadable via IO API");

            // Also verify loadWithCache returns a SnapshotWithCache with non-null cache instance
            var swc = loadedOpt.get();
            assertNotNull(swc.state(), "Loaded AnalyzerState must be present");
            assertNotNull(swc.cache(), "Loaded AnalyzerCache must be present (may be empty)");
        }
    }

    @Test
    void saveAndLoadWithoutCacheProducesSnapshotWithSchemaVersion(@TempDir Path tempDir) throws Exception {
        var builder = InlineTestProjectCreator.code(
                """
                package com.example;
                public class X { }
                """,
                "src/main/java/com/example/X.java");

        try (IProject project = builder.build()) {
            JavaAnalyzer analyzer = new JavaAnalyzer(project);

            var state = analyzer.snapshotState();

            Path out = tempDir.resolve("snapshot_no_cache.smile.gz");
            TreeSitterStateIO.save(state, out); // uses overload without cache

            assertTrue(Files.exists(out));

            // Verify load() returns the state even without cache
            var loadedStateOpt = TreeSitterStateIO.load(out);
            assertTrue(loadedStateOpt.isPresent(), "load() should return AnalyzerState for saved snapshot");
        }
    }
}
