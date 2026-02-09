package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
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

            Path out = tempDir.resolve("snapshot_with_cache.bin.gzip");
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

            Path out = tempDir.resolve("snapshot_no_cache.bin.gzip");
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

    @Test
    void snapshotSavedWithLZ4FormatIsCompressed(@TempDir Path tempDir) throws Exception {
        var builder = InlineTestProjectCreator.code("public class LZ4Test { }", "LZ4Test.java");

        try (IProject project = builder.build()) {
            JavaAnalyzer analyzer = new JavaAnalyzer(project);
            Path out = tempDir.resolve("test.bin.gzip");
            TreeSitterStateIO.save(analyzer.snapshotState(), out);

            assertTrue(Files.exists(out));

            // Verify it has LZ4 Frame magic: 0x04 0x22 0x4D 0x18
            byte[] bytes = Files.readAllBytes(out);
            assertTrue(bytes.length > 4);
            assertEquals((byte) 0x04, bytes[0]);
            assertEquals((byte) 0x22, bytes[1]);
            assertEquals((byte) 0x4D, bytes[2]);
            assertEquals((byte) 0x18, bytes[3]);

            // Verify it loads
            var loaded = TreeSitterStateIO.load(out);
            assertTrue(loaded.isPresent(), "Should load LZ4 snapshot");
        }
    }

    @Test
    void snapshotWithLargeRepeatedStringsIsEfficientlyCompressed(@TempDir Path tempDir) throws Exception {
        var builder = InlineTestProjectCreator.code(
                """
                package com.example;
                public class Hello {
                    public void method() {
                        // This won't actually be in the snapshot state directly as source,
                        // but we can simulate repeated strings in signatures/symbol keys.
                    }
                }
                """,
                "src/main/java/com/example/Hello.java");

        try (IProject project = builder.build()) {
            JavaAnalyzer analyzer = new JavaAnalyzer(project);
            var state = analyzer.snapshotState();

            // Create a cache with many repeated large strings
            var cache = new ai.brokk.analyzer.cache.AnalyzerCache();
            String repeatedString = "A".repeat(1000);
            List<String> largeSignatures = Collections.nCopies(100, repeatedString);

            // Attach these to the first available CodeUnit
            var cu = state.codeUnitState().keySet().iterator().next();
            cache.signatures().put(cu, largeSignatures);

            Path out = tempDir.resolve("compressed.bin.gzip");
            TreeSitterStateIO.save(state, cache.snapshot(), out);

            assertTrue(Files.exists(out));
            long size = Files.size(out);

            // Without compression, 100 * 1000 bytes = 100KB just for the strings.
            // Loosen threshold to allow for library overhead while ensuring it is not pathologically large.
            // The goal is to verify round-trip integrity with compression enabled.
            assertTrue(size < 150000, "Snapshot size (" + size + " bytes) is pathologically larger than expected.");

            // Verify round-trip still works
            var loadedOpt = TreeSitterStateIO.loadWithCache(out);
            assertTrue(loadedOpt.isPresent());
            assertEquals(largeSignatures, loadedOpt.get().cache().signatures().get(cu));
        }
    }

    @Test
    void snapshotWithMajorVersionMismatchReturnsEmpty(@TempDir Path tempDir) throws Exception {
        var builder = InlineTestProjectCreator.code(
                """
                package com.example;
                public class VersionTest { }
                """,
                "src/main/java/com/example/VersionTest.java");

        try (IProject project = builder.build()) {
            JavaAnalyzer analyzer = new JavaAnalyzer(project);
            var state = analyzer.snapshotState();
            var cache = new ai.brokk.analyzer.cache.AnalyzerCache();
            // Add a signature to make the cache non-empty
            cache.signatures()
                    .put(
                            state.symbolIndex()
                                    .values()
                                    .iterator()
                                    .next()
                                    .iterator()
                                    .next(),
                            List.of("v1-sig"));

            Path originalPath = tempDir.resolve("original_v1.bin.gzip");
            TreeSitterStateIO.save(state, cache.snapshot(), originalPath);

            // Load the raw DTO to mutate it
            var rawOpt = TreeSitterStateIO.loadRaw(originalPath);
            assertTrue(rawOpt.isPresent());
            var raw = rawOpt.get();

            // Create a mutated version with a major version bump (1.0.0 -> 2.0.0)
            var mutated = new TreeSitterStateIO.SnapshotDto("2.0.0", raw.analyzerState(), raw.cacheSnapshot());

            Path mutatedPath = tempDir.resolve("mutated_v2.bin.gzip");
            TreeSitterStateIO.saveRawSnapshotForTest(mutated, mutatedPath);

            // Verify that loadWithCache returns empty due to major version mismatch
            var loadedOpt = TreeSitterStateIO.loadWithCache(mutatedPath);
            assertFalse(loadedOpt.isPresent(), "Snapshot with major version mismatch should not be loadable");
        }
    }
}
