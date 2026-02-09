package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

            var state = analyzer.snapshotState();
            var cache = new ai.brokk.analyzer.cache.AnalyzerCache();
            // Populate something so we aren't just testing empty state persistence
            cache.signatures()
                    .put(
                            state.symbolIndex()
                                    .values()
                                    .iterator()
                                    .next()
                                    .iterator()
                                    .next(),
                            List.of("test-sig"));
            var cacheSnapshot = cache.snapshot();

            Path out = tempDir.resolve("snapshot_with_cache.bin.gz");
            TreeSitterStateIO.save(state, cacheSnapshot, out);

            assertTrue(Files.exists(out), "Snapshot file should have been written");

            // Verify raw snapshot DTO structure via Fory helper
            var rawOpt = TreeSitterStateIO.loadRaw(out);
            assertTrue(rawOpt.isPresent(), "Raw snapshot should be loadable");
            var raw = rawOpt.get();
            assertFalse(raw.schemaVersion().isBlank(), "schemaVersion should not be blank");
            assertEquals(TreeSitterStateIO.SCHEMA_VERSION, raw.schemaVersion());
            assertNotNull(raw.cacheSnapshot(), "cacheSnapshot should be present when saved with one");

            // Verify full rehydration
            var loadedOpt = TreeSitterStateIO.loadWithCache(out);
            assertTrue(loadedOpt.isPresent(), "Snapshot should be loadable via IO API");
            var swc = loadedOpt.get();
            assertNotNull(swc.state());
            assertNotNull(swc.cache());
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

            Path out = tempDir.resolve("snapshot_no_cache.bin.gz");
            TreeSitterStateIO.save(state, out); // uses overload without cache

            assertTrue(Files.exists(out));

            // Verify raw snapshot DTO structure
            var rawOpt = TreeSitterStateIO.loadRaw(out);
            assertTrue(rawOpt.isPresent());
            var raw = rawOpt.get();
            assertFalse(raw.schemaVersion().isBlank());
            assertNotNull(raw.analyzerState());
            assertNull(raw.cacheSnapshot(), "cacheSnapshot should be null when saved without one");

            // Verify load() returns the state even without cache
            var loadedStateOpt = TreeSitterStateIO.load(out);
            assertTrue(loadedStateOpt.isPresent(), "load() should return AnalyzerState for saved snapshot");
        }
    }
}
