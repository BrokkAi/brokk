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
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
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

        // Ensure dummy project files exist and content placeholders are created
        var fragmentsJsonPath = staging.resolve("fragments-v3.json");
        ensureProjectFilesExist(projectRoot, fragmentsJsonPath);
        ensureContentPlaceholdersExist(staging);

        // Sanity: ensure all logIds referenced in contexts.jsonl have a corresponding task entry in fragments-v3.json
        assertAllContextLogIdsHaveTaskEntries(staging);

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

    @Test
    void testPathFragmentWithoutType_Compatibility() throws IOException, URISyntaxException {
        // Stage the minimal path-fragment V3 session that omits "type" on files within FrozenFragmentDto
        var resourceUri = requireNonNull(
                        HistoryIoV3CompatibilityTest.class.getResource("/context-fragments/v3-path-frag-without-type"))
                .toURI();
        var resourcePath = Path.of(resourceUri);
        var staging = tempDir.resolve("staging-pathfrag");
        FileUtil.copyDirectory(resourcePath, staging);

        // Ensure dummy project files exist for all relPaths referenced in fragments-v3.json
        var fragmentsJsonPath = staging.resolve("fragments-v3.json");
        ensureProjectFilesExist(projectRoot, fragmentsJsonPath);

        // Ensure minimal content/ placeholders exist for all referenced content IDs
        ensureContentPlaceholdersExist(staging);

        // Zip and load.
        Path zipPath = tempDir.resolve("history-pathfrag.zip");
        FileUtil.zipDirectory(staging, zipPath);

        ContextHistory history = assertDoesNotThrow(
                () -> HistoryIo.readZip(zipPath, mockContextManager),
                "Deserialization should not throw for V3 path fragment without 'type' discriminator");
        assertNotNull(history, "ContextHistory should not be null");
        assertFalse(history.getHistory().isEmpty(), "Contexts should not be empty");

        Context live = history.liveContext();
        // Let fragments materialize
        live.awaitContextsAreComputed(Duration.ofSeconds(10));

        var ppf = findFragment(live, ContextFragment.ProjectPathFragment.class, f -> true);
        assertNotNull(ppf, "ProjectPathFragment should be present");
        var description = ppf.computedDescription().renderNowOrNull();
        var path = String.join(File.separator, List.of("app", "src", "main", "java", "ai", "brokk"));
        assertEquals("EditBlock.java [%s]".formatted(path), description);
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

        // Ensure dummy project files and content placeholders based on the fixture
        ensureProjectFilesExist(projectRoot, jsonPath);
        ensureContentPlaceholdersExist(staging);

        Path zipPath = tempDir.resolve("history.zip");
        FileUtil.zipDirectory(staging, zipPath);
        return zipPath;
    }

    /**
     * Ensures that all project-relative files referenced by the V3 fragments exist under the provided project root.
     * It extracts relPath fields from fragments-v3.json (referenced.*.files[*].relPath) and creates dummy files.
     */
    private static void ensureProjectFilesExist(Path projectRoot, Path fragmentsJsonPath) throws IOException {
        if (!Files.exists(fragmentsJsonPath)) {
            return;
        }
        var mapper = new ObjectMapper();
        var root = mapper.readTree(Files.readString(fragmentsJsonPath, StandardCharsets.UTF_8));
        var relPaths = new HashSet<String>();

        var referenced = root.get("referenced");
        if (referenced != null && referenced.isObject()) {
            var fields = referenced.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                var dto = entry.getValue();

                // Prefer files[*].relPath if present
                var filesNode = dto.get("files");
                if (filesNode != null && filesNode.isArray()) {
                    for (JsonNode fn : filesNode) {
                        var rel = fn.get("relPath");
                        if (rel != null && rel.isTextual()) {
                            relPaths.add(rel.asText());
                        }
                    }
                }

                // Fallback: some legacy items also carry meta.relPath
                var meta = dto.get("meta");
                if (meta != null && meta.isObject()) {
                    var rel = meta.get("relPath");
                    if (rel != null && rel.isTextual()) {
                        relPaths.add(rel.asText());
                    }
                }
            }
        }

        for (String rel : relPaths) {
            FileUtil.createDummyFile(projectRoot, rel);
        }
    }

    /**
     * Ensures a minimal content/ directory exists with placeholder <contentId>.txt files for every content id referenced by
     * fragments-v3.json ("contentId"/"diffContentId") and content_metadata.json (map keys).
     * For any content IDs referenced as summaryContentId in contexts.jsonl, ensure the file is non-empty to satisfy
     * TaskEntry.fromCompressed() invariant.
     */
    private static void ensureContentPlaceholdersExist(Path staging) throws IOException {
        var mapper = new ObjectMapper();
        var contentIds = new HashSet<String>();
        var nonEmptyIds = new HashSet<String>(); // IDs that must have non-empty content (e.g., summaryContentId)

        // 1) Scan fragments-v3.json for "contentId"/"diffContentId"
        var fragmentsJsonPath = staging.resolve("fragments-v3.json");
        if (Files.exists(fragmentsJsonPath)) {
            var fragsNode = mapper.readTree(Files.readString(fragmentsJsonPath, StandardCharsets.UTF_8));
            var stack = new java.util.ArrayDeque<JsonNode>();
            stack.push(fragsNode);
            while (!stack.isEmpty()) {
                JsonNode n = stack.pop();
                if (n.isObject()) {
                    var fields = n.fields();
                    while (fields.hasNext()) {
                        var e = fields.next();
                        var key = e.getKey();
                        var val = e.getValue();
                        if (("contentId".equals(key) || "diffContentId".equals(key)) && val.isTextual()) {
                            contentIds.add(val.asText());
                        }
                        stack.push(val);
                    }
                } else if (n.isArray()) {
                    for (JsonNode c : n) stack.push(c);
                }
            }
        }

        // 2) Scan content_metadata.json for all keys
        var contentMetadataPath = staging.resolve("content_metadata.json");
        if (Files.exists(contentMetadataPath)) {
            var metaNode = mapper.readTree(Files.readString(contentMetadataPath, StandardCharsets.UTF_8));
            var it = metaNode.fieldNames();
            while (it.hasNext()) {
                contentIds.add(it.next());
            }
        }

        // 3) Scan contexts.jsonl for summaryContentId and ensure those are non-empty
        var contextsPath = staging.resolve("contexts.jsonl");
        if (Files.exists(contextsPath)) {
            var lines = Files.readAllLines(contextsPath, StandardCharsets.UTF_8);
            for (var line : lines) {
                var trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    var node = mapper.readTree(trimmed);
                    var tasks = node.get("tasks");
                    if (tasks != null && tasks.isArray()) {
                        for (JsonNode t : tasks) {
                            var sid = t.get("summaryContentId");
                            if (sid != null && sid.isTextual()) {
                                nonEmptyIds.add(sid.asText());
                                contentIds.add(sid.asText());
                            }
                        }
                    }
                } catch (Exception ignore) {
                    // If a line isn't valid JSON, skip it; tests ensure well-formed contexts
                }
            }
        }

        // 4) Write placeholder files under content/<id>.txt
        if (!contentIds.isEmpty()) {
            var contentDir = staging.resolve("content");
            Files.createDirectories(contentDir);
            for (var cid : contentIds) {
                var contentFile = contentDir.resolve(cid + ".txt");
                if (nonEmptyIds.contains(cid)) {
                    byte[] data = ("placeholder content for " + cid).getBytes(StandardCharsets.UTF_8);
                    if (!Files.exists(contentFile) || Files.size(contentFile) == 0) {
                        Files.write(contentFile, data);
                    }
                } else {
                    if (!Files.exists(contentFile)) {
                        Files.writeString(contentFile, "Hello world!");
                    }
                }
            }
        }
    }

    /**
     * Verifies that every logId referenced in contexts.jsonl has a corresponding entry in fragments-v3.json's "task" map.
     * This ensures that logId like 7db17bee5842f520642beb700c144f36f93d761693818a61160374e3da010c09 is present.
     */
    private static void assertAllContextLogIdsHaveTaskEntries(Path staging) throws IOException {
        var contextsPath = staging.resolve("contexts.jsonl");
        if (!Files.exists(contextsPath)) {
            return; // nothing to validate
        }

        var mapper = new ObjectMapper();
        var logIds = new java.util.HashSet<String>();
        var lines = Files.readAllLines(contextsPath, StandardCharsets.UTF_8);
        for (var line : lines) {
            var trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            try {
                var node = mapper.readTree(trimmed);
                var tasks = node.get("tasks");
                if (tasks != null && tasks.isArray()) {
                    for (JsonNode t : tasks) {
                        var lid = t.get("logId");
                        if (lid != null && lid.isTextual()) {
                            logIds.add(lid.asText());
                        }
                    }
                }
            } catch (Exception ignore) {
                // Skip invalid JSON lines; other tests ensure contexts.jsonl is valid as needed
            }
        }

        if (logIds.isEmpty()) {
            return; // nothing to validate
        }

        var fragmentsPath = staging.resolve("fragments-v3.json");
        if (!Files.exists(fragmentsPath)) {
            fail("fragments-v3.json not found but contexts.jsonl references logIds: " + logIds);
        }

        var root = mapper.readTree(Files.readString(fragmentsPath, StandardCharsets.UTF_8));
        var taskNode = root.get("task");
        var taskIds = new java.util.HashSet<String>();
        if (taskNode != null && taskNode.isObject()) {
            var it = taskNode.fieldNames();
            while (it.hasNext()) {
                taskIds.add(it.next());
            }
        }

        for (String id : logIds) {
            assertTrue(taskIds.contains(id), "Missing task entry in fragments-v3.json for logId: " + id);
        }
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
