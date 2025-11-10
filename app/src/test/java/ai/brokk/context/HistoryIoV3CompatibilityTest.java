package ai.brokk.context;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IContextManager;
import ai.brokk.testutil.FileUtil;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.util.HistoryIo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryIoV3CompatibilityTest {
    @TempDir
    Path tempDir;

    private IContextManager mockContextManager;
    private Path projectRoot;

    @BeforeEach
    void setup() throws IOException {
        projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        mockContextManager = new TestContextManager(projectRoot, new NoOpConsoleIO());
    }

    @Test
    void testBasicSessionCompatibility() throws IOException, URISyntaxException {
        Path zipPath = stageBasicSessionZip();

        ContextHistory history = HistoryIo.readZip(zipPath, mockContextManager);

        assertNotNull(history, "History should not be null");
        assertFalse(history.getHistory().isEmpty(), "History contexts should not be empty");

        Context top = history.liveContext();
        // Let fragments materialize
        top.awaitContextsAreComputed(Duration.ofSeconds(10));

        var projectPathFragment = findFragment(top, ContextFragment.ProjectPathFragment.class, f -> f.description()
                .contains("GitHubAuth.java"));
        assertNotNull(projectPathFragment, "ProjectPathFragment for GitHubAuth.java should be present");

        var buildFragment = findFragment(
                top,
                ContextFragment.StringFragment.class,
                f -> "Source code for io.github.jbellis.brokk.Completions.expandPath".equals(f.description()));
        assertNotNull(buildFragment, "Migrated BuildFragment (as StringFragment) should be present");

        var imageFileFragment = findFragment(top, ContextFragment.ImageFileFragment.class, f -> f.description()
                .contains("ai-robot.png"));
        assertNotNull(imageFileFragment, "ImageFileFragment for ai-robot.png should be present");
        assertTrue(
                imageFileFragment.file().absPath().toString().endsWith("ai-robot.png"),
                "ImageFileFragment path should be correct");
    }

    @Test
    void testRealworldCompleteSessionCompatibility() throws IOException, URISyntaxException {
        // Stage the real-world v3 session
        var resourceUri = requireNonNull(
                        HistoryIoV3CompatibilityTest.class.getResource("/context-fragments/v3-realworld-complete"))
                .toURI();
        var resourcePath = Path.of(resourceUri);
        var staging = tempDir.resolve("staging-realworld");
        FileUtil.copyDirectory(resourcePath, staging);

        // No path patching needed for this fixture; just zip and load
        var zipPathRealworld = tempDir.resolve("history-realworld.zip");
        FileUtil.zipDirectory(staging, zipPathRealworld);

        // Sanity: zip should contain expected entries with non-empty content
        assertZipHasNonEmptyEntry(zipPathRealworld, "fragments-v3.json");
        assertZipHasNonEmptyEntry(zipPathRealworld, "contexts.jsonl");
        assertZipHasNonEmptyEntry(zipPathRealworld, "content_metadata.json");
        assertZipHasNonEmptyEntry(zipPathRealworld, "git_states.json");
        assertZipHasNonEmptyEntry(zipPathRealworld, "manifest.json");
        assertZipHasNonEmptyEntry(zipPathRealworld, "tasklist.json");

        // Focused: content_metadata.json should parse as non-empty JSON object.
        String contentMetadataJson = readZipEntryAsString(zipPathRealworld, "content_metadata.json");
        assertJsonObjectNonEmpty(contentMetadataJson, "content_metadata.json should be a non-empty JSON object");

        // Focused: fragments-v3.json referenced entries should be ai.brokk.* classes (not legacy io.github.jbellis.*)
        assertFragmentsJsonReferencesAreBrokk(zipPathRealworld);

        // Load through the same V3 reader/migration flow; ensure no InvalidTypeIdException is thrown.
        ContextHistory history = assertDoesNotThrow(
                () -> HistoryIo.readZip(zipPathRealworld, mockContextManager),
                "Deserialization should not throw InvalidTypeIdException");

        // Validate basic deserialization
        assertNotNull(history, "ContextHistory should not be null");
        assertFalse(history.getHistory().isEmpty(), "Contexts should not be empty");
        Context live = history.liveContext();
        assertNotNull(live, "Live context should not be null");
        assertTrue(live.allFragments().findAny().isPresent(), "Live context should have fragments");

        // Prior failure focus: ensure fragments map to ai.brokk runtime classes (not legacy io.github.jbellis.*)
        var hasLegacy = live.allFragments()
                .map(f -> f.getClass().getName())
                .anyMatch(name -> name.startsWith("io.github.jbellis."));
        assertFalse(hasLegacy, "Fragments should resolve to ai.brokk.* runtime classes, not io.github.jbellis.*");
    }

    private Path stageBasicSessionZip() throws IOException, URISyntaxException {
        var resourceUri = requireNonNull(
                        HistoryIoV3CompatibilityTest.class.getResource("/context-fragments/v3-small-complete"))
                .toURI();
        var resourcePath = Path.of(resourceUri);
        var staging = tempDir.resolve("staging");
        FileUtil.copyDirectory(resourcePath, staging);

        var jsonPath = staging.resolve("fragments-v3.json");
        String content = Files.readString(jsonPath, StandardCharsets.UTF_8);
        String sanitizedRoot = projectRoot.toString().replace('\\', '/');
        String updatedContent = content.replace("/Users/dave/Workspace/BrokkAi/brokk", sanitizedRoot);
        Files.writeString(jsonPath, updatedContent, StandardCharsets.UTF_8);

        // Create required dummy files referenced by the test fixture
        FileUtil.createDummyFile(projectRoot, "app/src/main/java/io/github/jbellis/brokk/GitHubAuth.java");
        FileUtil.createDummyFile(projectRoot, "app/src/main/resources/icons/light/ai-robot.png");
        FileUtil.createDummyFile(projectRoot, "app/src/main/java/io/github/jbellis/brokk/AbstractProject.java");
        FileUtil.createDummyFile(projectRoot, "app/src/main/java/io/github/jbellis/brokk/IProject.java");

        Path zipPath = tempDir.resolve("history.zip");
        FileUtil.zipDirectory(staging, zipPath);
        return zipPath;
    }

    private static String readZipEntryAsString(Path zip, String entryName) throws IOException {
        try (var zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        fail("Missing expected zip entry: " + entryName);
        return ""; // Unreachable
    }

    private static void assertJsonObjectNonEmpty(String json, String message) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(json);
        assertTrue(node.isObject(), message + " (not an object)");
        assertTrue(node.size() > 0, message + " (empty object)");
    }

    private static void assertFragmentsJsonReferencesAreBrokk(Path zip) throws IOException {
        String fragmentsJson = readZipEntryAsString(zip, "fragments-v3.json");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(fragmentsJson);
        JsonNode referenced = root.get("referenced");
        assertNotNull(referenced, "'referenced' section should be present in fragments-v3.json");
        assertTrue(referenced.size() > 0, "'referenced' section should be non-empty");

        for (var it = referenced.elements(); it.hasNext(); ) {
            JsonNode entry = it.next();
            JsonNode classNode = entry.get("@class");
            assertNotNull(classNode, "Each referenced entry should contain an '@class' field");
            String clazz = classNode.asText();
            assertTrue(clazz.startsWith("ai.brokk."), "Class should map to ai.brokk.* but got: " + clazz);
            assertFalse(
                    clazz.startsWith("io.github.jbellis."), "Class should not be legacy io.github.jbellis.*: " + clazz);
        }
    }

    /**
     * Helper function to find a ContextFragment of a specific type using a given predicate.
     *
     * @param context   the context to sift through.
     * @param type      the fragment type.
     * @param condition the predicate on which to map the fragment of type "type".
     * @param <T>       The ContextFragment type.
     * @return the fragment if found, null otherwise.
     */
    private @Nullable <T extends ContextFragment> T findFragment(
            Context context, Class<T> type, java.util.function.Predicate<T> condition) {
        return context.allFragments()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(condition)
                .findFirst()
                .orElse(null);
    }

    private static void assertZipHasNonEmptyEntry(Path zip, String entryName) throws IOException {
        try (var zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    byte[] data = zis.readAllBytes();
                    assertNotNull(data, entryName + " should be present in zip");
                    assertTrue(data.length > 0, entryName + " should have non-empty content");
                    return;
                }
            }
        }
        fail("Missing expected zip entry: " + entryName);
    }
}
