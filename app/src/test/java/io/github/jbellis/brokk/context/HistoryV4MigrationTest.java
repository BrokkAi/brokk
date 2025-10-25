package io.github.jbellis.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.testutil.NoOpConsoleIO;
import io.github.jbellis.brokk.testutil.TestContextManager;
import io.github.jbellis.brokk.util.HistoryIo;
import io.github.jbellis.brokk.util.migrationv4.HistoryV4Migrator;
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
 * io.github.jbellis.brokk.util.migrationv4.HistoryV4Migrator} can process various V3 archives
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
        MainProject.setLlmProxySetting(MainProject.LlmProxySetting.LOCALHOST);
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
