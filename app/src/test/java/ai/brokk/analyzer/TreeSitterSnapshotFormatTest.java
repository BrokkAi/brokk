package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
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

            // Read raw top-level SnapshotDto using Jackson to inspect schemaVersion
            ObjectMapper mapper =
                    new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            try (var in = new GZIPInputStream(Files.newInputStream(out))) {
                TreeSitterStateIO.SnapshotDto top = mapper.readValue(in, TreeSitterStateIO.SnapshotDto.class);
                assertNotNull(top, "Top-level snapshot DTO should deserialize");
                assertNotNull(top.schemaVersion(), "schemaVersion field must be present");
                assertFalse(top.schemaVersion().isBlank(), "schemaVersion must be non-empty");
                // cacheSnapshot may be null in some paths; when present, ensure structure exists
                if (top.cacheSnapshot() != null) {
                    assertNotNull(top.cacheSnapshot().signatures());
                    assertNotNull(top.cacheSnapshot().rawSupertypes());
                }
            }

            // Also verify loadWithCache returns a SnapshotWithCache with non-null cache instance
            var loadedOpt = TreeSitterStateIO.loadWithCache(out);
            assertTrue(loadedOpt.isPresent(), "loadWithCache should return a value for a valid snapshot");
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

            ObjectMapper mapper =
                    new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            try (var in = new GZIPInputStream(Files.newInputStream(out))) {
                TreeSitterStateIO.SnapshotDto top = mapper.readValue(in, TreeSitterStateIO.SnapshotDto.class);
                assertNotNull(
                        top.schemaVersion(), "schemaVersion must be present even when no cache snapshot provided");
                assertFalse(top.schemaVersion().isBlank(), "schemaVersion must be non-empty");
            }

            // load() should return Optional<AnalyzerState>
            var loadedStateOpt = TreeSitterStateIO.load(out);
            assertTrue(loadedStateOpt.isPresent(), "load() should return AnalyzerState for saved snapshot");
        }
    }
}
