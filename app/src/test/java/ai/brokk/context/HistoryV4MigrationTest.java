package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IContextManager;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.util.HistoryIo;
import ai.brokk.util.migrationv4.HistoryV4Migrator;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.Collections;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Test suite for verifying the migration of V3 history zip files to the V4 format.
 *
 * <p>This test class focuses on the integrity of the migration process itself, ensuring that {@link
 * ai.brokk.util.migrationv4.HistoryV4Migrator} can process various V3 archives
 * without throwing exceptions. It uses a parameterized test to run the migration on a collection of
 * V3 zip files, each containing different fragment types and history states.
 *
 * <p>This differs from {@link HistoryIoV3CompatibilityTest}, which is responsible for verifying the
 * correctness of reading and deserializing a V3-formatted zip file into modern V4 in-memory
 * objects, but does not test the file-to-file migration process.
 */
class HistoryV4MigrationTest {
    @TempDir
    Path tempDir;

    private IContextManager mockContextManager;

    @BeforeEach
    void setup() throws IOException {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        mockContextManager = new TestContextManager(projectRoot, new NoOpConsoleIO());
    }

    static Stream<String> v3ZipProvider() throws URISyntaxException, IOException {
        var resourceFolder = "/context-fragments/generated-v3-zips-for-migration/";
        var resourceUrl = HistoryV4MigrationTest.class.getResource(resourceFolder);
        if (resourceUrl == null) {
            throw new IOException("Resource folder not found: " + resourceFolder);
        }
        var uri = resourceUrl.toURI();

        Path resourcePath;
        if ("jar".equals(uri.getScheme())) {
            try (FileSystem fileSystem = FileSystems.newFileSystem(uri, Collections.emptyMap())) {
                resourcePath = fileSystem.getPath(resourceFolder);
                // Need to collect to a list as the filesystem is closed
                try (var paths = Files.walk(resourcePath, 1)) {
                    return paths
                            .filter(path -> path.toString().endsWith(".zip"))
                            .map(path -> path.getFileName().toString())
                            .sorted()
                            .toList()
                            .stream();
                }
            }
        } else {
            resourcePath = Paths.get(uri);
            try (var paths = Files.walk(resourcePath, 1)) {
                return paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".zip"))
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .toList()
                        .stream();
            }
        }
    }

    @ParameterizedTest
    @MethodSource("v3ZipProvider")
    void testMigrateV3Zip(String zipFileName) throws IOException {
        var resourcePath = "/context-fragments/generated-v3-zips-for-migration/" + zipFileName;
        Path tempZip = tempDir.resolve(zipFileName);

        try (var is = HistoryV4MigrationTest.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            Files.copy(is, tempZip);
        }

        assertDoesNotThrow(
                () -> HistoryV4Migrator.migrate(tempZip, mockContextManager),
                "Migration should succeed for " + zipFileName);

        var history = HistoryIo.readZip(tempZip, mockContextManager);
        assertNotNull(history);

        if ("v3-complex-content.zip".equals(zipFileName)) {
            assertEquals(2, history.getHistory().size());
            var ctx1 = history.getHistory().get(0);
            assertEquals(2, ctx1.allFragments().count());
            var ff = findFragment(
                    ctx1, FrozenFragment.class, f -> f.originalClassName().contains("ProjectPathFragment"));
            assertNotNull(ff);
            assertTrue(ff.description().contains("File1.java"));

            var sf = findFragment(ctx1, ContextFragment.StringFragment.class, f -> true);
            assertNotNull(sf);
            assertEquals("Virtual content 1", sf.text());

            var ctx2 = history.getHistory().get(1);
            assertEquals(1, ctx2.virtualFragments().count());
            var pif = findFragment(ctx2, ContextFragment.AnonymousImageFragment.class, f -> true);
            assertNotNull(pif);
            assertEquals("Pasted Red Image", pif.description());
            assertNotNull(pif.imageBytes());
            assertTrue(pif.imageBytes().length > 0);

            assertEquals(1, ctx2.getTaskHistory().size());
            var taskEntry = ctx2.getTaskHistory().get(0);
            assertNotNull(taskEntry.log());
            assertEquals(2, taskEntry.log().messages().size());
        } else if ("v3-gitfile-fragment-only.zip".equals(zipFileName)) {
            assertEquals(1, history.getHistory().size());
            var ctx = history.getLiveContext();
            assertEquals(1, ctx.fileFragments().count());
            var gff = findFragment(ctx, ContextFragment.GitFileFragment.class, f -> true);
            assertNotNull(gff);
            assertEquals("abcdef1234567890", gff.revision());
            assertEquals("content for git file", gff.content());
        } else if ("v3-imagefile-fragment-only.zip".equals(zipFileName)) {
            assertEquals(1, history.getHistory().size());
            var ctx = history.getLiveContext();
            assertEquals(1, ctx.allFragments().count());
            var iff = findFragment(ctx, ContextFragment.ImageFileFragment.class, f -> true);
            assertNotNull(iff);
            // We don't assert on image() because it requires the file to be on disk,
            // and the migration test context doesn't extract it.
            assertEquals("test_image.png", iff.file().getFileName());
        } else if ("v3-shared-image-fragment.zip".equals(zipFileName)) {
            assertEquals(2, history.getHistory().size());
            var ctx1 = history.getHistory().get(0);
            var aif = findFragment(ctx1, ContextFragment.AnonymousImageFragment.class, f -> true);
            assertNotNull(aif);
            assertNotNull(aif.image());

            var ctx2 = history.getHistory().get(1);
            var iff = findFragment(ctx2, ContextFragment.AnonymousImageFragment.class, f -> true);
            assertNotNull(iff);
            assertNotNull(iff.image());
        } else if ("v3-anonymous-image-fragment.zip".equals(zipFileName)) {
            assertEquals(1, history.getHistory().size());
            var ctx = history.getLiveContext();
            assertEquals(1, ctx.allFragments().count());
            var aif = findFragment(ctx, ContextFragment.AnonymousImageFragment.class, f -> true);
            assertNotNull(aif);
            assertNotNull(aif.image());
        } else if ("v3-task-fragment.zip".equals(zipFileName)) {
            assertEquals(1, history.getHistory().size());
            var ctx = history.getLiveContext();
            assertEquals(1, ctx.allFragments().count());
            var tf = findFragment(ctx, ContextFragment.TaskFragment.class, f -> true);
            assertNotNull(tf);
            assertEquals("Test Task Fragment", tf.description());
        } else if ("v3-string-fragment.zip".equals(zipFileName)) {
            assertEquals(1, history.getHistory().size());
            var ctx = history.getLiveContext();
            assertEquals(1, ctx.allFragments().count());
            var sf = findFragment(ctx, ContextFragment.StringFragment.class, f -> true);
            assertNotNull(sf);
            assertEquals("some description", sf.description());
            assertEquals("some text", sf.text());
        } else if ("v3-projectpath-fragment.zip".equals(zipFileName)) {
            assertEquals(1, history.getHistory().size());
            var ctx = history.getLiveContext();
            assertEquals(1, ctx.allFragments().count());
            var ppf = findFragment(ctx, ContextFragment.ProjectPathFragment.class, f -> true);
            assertNotNull(ppf);
            assertTrue(ppf.description().contains("ProjectPath.java"));
        } else if ("v3-externalpath-fragment.zip".equals(zipFileName)) {
            assertEquals(1, history.getHistory().size());
            var ctx = history.getLiveContext();
            assertEquals(1, ctx.allFragments().count());
            var epf = findFragment(ctx, ContextFragment.ExternalPathFragment.class, f -> true);
            assertNotNull(epf);
            assertTrue(epf.file().toString().endsWith("external_file.txt"));
            assertEquals("", epf.text());
        } else if ("v3-search-fragment.zip".equals(zipFileName)) {
            assertEquals(1, history.getHistory().size());
            var ctx = history.getLiveContext();
            assertEquals(1, ctx.allFragments().count());
            var sf = findFragment(ctx, ContextFragment.SearchFragment.class, f -> true);
            assertNotNull(sf);
            assertEquals("Search: foobar", sf.description());
            assertFalse(sf.messages().isEmpty());
        } else if ("v3-skeleton-fragment.zip".equals(zipFileName)) {
            assertEquals(1, history.getHistory().size());
            var ctx = history.getLiveContext();
            assertEquals(2, ctx.allFragments().count());
            var sf = findFragment(ctx, ContextFragment.SummaryFragment.class, f -> true);
            assertNotNull(sf);
            assertTrue(sf.description().contains("Summary of com.example"));
            assertFalse(sf.getTargetIdentifiers().isEmpty());
        } else if ("v3-usage-fragment.zip".equals(zipFileName)) {
            assertEquals(1, history.getHistory().size());
            var ctx = history.getLiveContext();
            assertEquals(1, ctx.allFragments().count());
            var uf = findFragment(ctx, ContextFragment.UsageFragment.class, f -> true);
            assertNotNull(uf);
            assertEquals("Uses of com.example.MyClass.myMethod", uf.description());
            assertEquals("com.example.MyClass.myMethod", uf.targetIdentifier());
        } else if ("v3-code-fragment.zip".equals(zipFileName)) {
            assertEquals(1, history.getHistory().size());
            var ctx = history.getLiveContext();
            assertEquals(1, ctx.allFragments().count());
            var cf = findFragment(ctx, ContextFragment.CodeFragment.class, f -> true);
            assertNotNull(cf);
            assertTrue(cf.description().startsWith("Source for"));
            assertNotNull(cf.getCodeUnit());
        } else if ("v3-callgraph-fragment.zip".equals(zipFileName)) {
            assertEquals(1, history.getHistory().size());
            var ctx = history.getLiveContext();
            assertEquals(1, ctx.allFragments().count());
            var cgf = findFragment(ctx, ContextFragment.CallGraphFragment.class, f -> true);
            assertNotNull(cgf);
            assertTrue(cgf.description().contains("Callees of"));
            assertEquals("com.example.MyClass.doStuff", cgf.getMethodName());
            assertEquals(3, cgf.getDepth());
            assertTrue(cgf.isCalleeGraph());
        } else if ("v3-history-fragment.zip".equals(zipFileName)) {
            assertEquals(1, history.getHistory().size());
            var ctx = history.getLiveContext();
            assertEquals(1, ctx.allFragments().count());
            var hf = findFragment(ctx, ContextFragment.HistoryFragment.class, f -> true);
            assertNotNull(hf);
            assertTrue(hf.description().startsWith("Task History"));
            assertFalse(hf.entries().isEmpty());
        } else if ("v3-pastetext-fragment.zip".equals(zipFileName)) {
            assertEquals(1, history.getHistory().size());
            var ctx = history.getLiveContext();
            assertEquals(1, ctx.allFragments().count());
            var ptf = findFragment(ctx, ContextFragment.PasteTextFragment.class, f -> true);
            assertNotNull(ptf);
            assertEquals("Pasted text content", ptf.text());
        } else if ("v3-stacktrace-fragment.zip".equals(zipFileName)) {
            assertEquals(1, history.getHistory().size());
            var ctx = history.getLiveContext();
            assertEquals(1, ctx.allFragments().count());
            var sf = findFragment(ctx, ContextFragment.StacktraceFragment.class, f -> true);
            assertNotNull(sf);
            assertEquals("stacktrace of NullPointerException", sf.description());
            assertTrue(sf.getOriginal().contains("Full stacktrace original text"));
        }
    }

    private @Nullable <T extends ContextFragment> T findFragment(
            Context context, Class<T> type, Predicate<T> condition) {
        return context.allFragments()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(condition)
                .findFirst()
                .orElse(null);
    }
}
