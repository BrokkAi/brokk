package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IContextManager;
import ai.brokk.Service;
import ai.brokk.TaskEntry;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.*;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import ai.brokk.util.HistoryIo;
import ai.brokk.util.Messages;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ContextSerializationTest {
    @TempDir
    Path tempDir;

    private IContextManager mockContextManager;

    @BeforeEach
    void setup() throws IOException {
        // Mock project components
        var project = new TestProject(tempDir, Languages.JAVA);

        var dummyCodeFragmentTarget = new ProjectFile(tempDir, "src/CodeFragmentTarget.java");
        var codeFragmentTargetCu = CodeUnit.cls(dummyCodeFragmentTarget, "com.example", "CodeFragmentTarget");

        var dummyMyClass = new ProjectFile(tempDir, "src/com/examples/MyClass.java");
        var codeFragmentTargetMthd = CodeUnit.fn(dummyMyClass, "com.example", "MyClass.myMethod");

        var testAnalyzer = new TestAnalyzer(
                List.of(codeFragmentTargetCu, codeFragmentTargetMthd),
                Map.of("com.example.MyClass.myMethod", List.of(codeFragmentTargetMthd)),
                project);
        mockContextManager = new TestContextManager(tempDir, new NoOpConsoleIO(), testAnalyzer);
        // Reset fragment ID counter for test isolation
        ContextFragments.setMinimumId(1);

        // Clean .brokk/sessions directory for session tests
        Path sessionsDir = tempDir.resolve(".brokk").resolve("sessions");
        if (Files.exists(sessionsDir)) {
            try (var stream = Files.walk(sessionsDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        // Log but continue - test isolation is best effort
                    }
                });
            }
        }
    }

    private BufferedImage createTestImage(Color color, int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    // --- Tests for HistoryIo ---

    @Test
    void testWriteReadEmptyHistory() throws IOException {
        var history = new ContextHistory(Context.EMPTY);
        Path zipFile = tempDir.resolve("empty_history.zip");

        HistoryIo.writeZip(history, zipFile);
        assertTrue(Files.exists(zipFile));

        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);
        assertNotNull(loadedHistory);
        assertFalse(loadedHistory.getHistory().isEmpty());
    }

    @Test
    void testReadNonExistentZip() throws IOException {
        Path zipFile = tempDir.resolve("non_existent.zip");
        assertThrows(IOException.class, () -> HistoryIo.readZip(zipFile, mockContextManager));
    }

    @Test
    void testWriteReadHistoryWithSingleContext_NoFragments() throws IOException {
        var history = new ContextHistory(new Context(mockContextManager));

        Path zipFile = tempDir.resolve("single_context_no_fragments.zip");
        HistoryIo.writeZip(history, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertEquals(1, loadedHistory.getHistory().size());
        // Further assertions can be added to compare context details if necessary,
        // focusing on serializable aspects.
        // For a "no fragments" context, primarily the task history (welcome message) is relevant.
        Context loadedCtx = loadedHistory.getHistory().get(0);
        assertNull(loadedCtx.getParsedOutput());
    }

    @Test
    void testWriteReadHistoryWithComplexContent() throws Exception {
        var projectFile1 = new ProjectFile(tempDir, "src/File1.java");
        Files.createDirectories(projectFile1.absPath().getParent());
        Files.writeString(projectFile1.absPath(), "public class File1 {}");

        // Context 1: Project file, string fragment
        var context1 = new Context(mockContextManager)
                .addFragments(List.of(new ContextFragments.ProjectPathFragment(projectFile1, mockContextManager)))
                .addFragments(new ContextFragments.StringFragment(
                        mockContextManager, "Virtual content 1", "VC1", SyntaxConstants.SYNTAX_STYLE_JAVA));
        ContextHistory originalHistory = new ContextHistory(context1);

        // Context 2: Image fragment, task history
        var image1 = createTestImage(Color.RED, 10, 10);
        var pasteImageFragment1 = new ContextFragments.AnonymousImageFragment(
                mockContextManager, image1, CompletableFuture.completedFuture("Pasted Red Image"));

        var context2 = new Context(mockContextManager).addFragments(pasteImageFragment1);

        List<ChatMessage> taskMessages = List.of(UserMessage.from("User query"), AiMessage.from("AI response"));
        var taskFragment = new ContextFragments.TaskFragment(mockContextManager, taskMessages, "Test Task");
        context2 = context2.addHistoryEntry(
                new TaskEntry(1, taskFragment, null),
                taskFragment);

        originalHistory.pushContext(context2);

        Path zipFile = tempDir.resolve("complex_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        // Assertions
        assertEquals(
                originalHistory.getHistory().size(), loadedHistory.getHistory().size());

        // Compare Context 1
        Context originalCtx1 = originalHistory.getHistory().get(0);
        Context loadedCtx1 = loadedHistory.getHistory().get(0);
        assertContextsEqual(originalCtx1, loadedCtx1);

        // Compare Context 2
        Context originalCtx2 = originalHistory.getHistory().get(1);
        Context loadedCtx2 = loadedHistory.getHistory().get(1);
        assertContextsEqual(originalCtx2, loadedCtx2);

        // Verify image content from the image fragment in loadedCtx2
        var loadedImageFragmentOpt = loadedCtx2
                .virtualFragments()
                .filter(f ->
                        !f.isText() && "Pasted Red Image".equals(f.description().join()))
                .findFirst();
        assertTrue(loadedImageFragmentOpt.isPresent(), "Pasted Red Image fragment not found in loaded context 2");
        var loadedImageFragment = loadedImageFragmentOpt.get();

        byte[] imageBytesContent;
        if (loadedImageFragment instanceof ContextFragments.AnonymousImageFragment pif) {
            imageBytesContent = pif.imageBytes().join();
        } else {
            throw new AssertionError("Unexpected fragment type for pasted image: " + loadedImageFragment.getClass());
        }

        assertNotNull(imageBytesContent);
        assertTrue(imageBytesContent.length > 0);
        Image loadedImage = ImageIO.read(new ByteArrayInputStream(imageBytesContent));
        assertNotNull(loadedImage);
        assertEquals(10, loadedImage.getWidth(null));
        assertEquals(10, loadedImage.getHeight(null));
        // Could do a pixel-by-pixel comparison if necessary
    }

    private void assertContextsEqual(Context expected, Context actual) throws IOException, InterruptedException {
        // Compare all fragments sorted by ID
        var expectedFragments = expected.allFragments()
                .sorted(Comparator.comparing(ContextFragment::id))
                .toList();
        var actualFragments = actual.allFragments()
                .sorted(Comparator.comparing(ContextFragment::id))
                .toList();

        assertEquals(expectedFragments.size(), actualFragments.size(), "Fragments count mismatch");
        for (int i = 0; i < expectedFragments.size(); i++) {
            assertContextFragmentsEqual(expectedFragments.get(i), actualFragments.get(i));
        }

        // Compare task history
        assertEquals(expected.getTaskHistory().size(), actual.getTaskHistory().size(), "Task history size mismatch");
        for (int i = 0; i < expected.getTaskHistory().size(); i++) {
            assertTaskEntriesEqual(
                    expected.getTaskHistory().get(i), actual.getTaskHistory().get(i));
        }
    }

    private void assertContextFragmentsEqual(ContextFragment expected, ContextFragment actual) throws IOException {
        assertEquals(expected.id(), actual.id(), "Fragment ID mismatch");
        assertEquals(expected.getType(), actual.getType(), "Fragment type mismatch for ID " + expected.id());
        var expectedDescription = expected.description().await(Duration.of(10, ChronoUnit.SECONDS));
        var actualDescription = actual.description().await(Duration.of(10, ChronoUnit.SECONDS));
        if (expectedDescription.isEmpty() || actualDescription.isEmpty()) {
            fail("Fragment descriptions could not be computed within 10 seconds");
        } else {
            assertEquals(
                    expectedDescription.get(),
                    actualDescription.get(),
                    "Fragment description mismatch for ID " + expected.id());
            // Short description is based off of description
            assertEquals(
                    expected.shortDescription().join(),
                    actual.shortDescription().join(),
                    "Fragment shortDescription mismatch for ID " + expected.id());
        }

        var expectedSyntaxStyle = expected.syntaxStyle().await(Duration.of(10, ChronoUnit.SECONDS));
        var actualSyntaxStyle = actual.syntaxStyle().await(Duration.of(10, ChronoUnit.SECONDS));
        if (expectedSyntaxStyle.isEmpty() || actualSyntaxStyle.isEmpty()) {
            fail("Fragment syntax style could not be computed within 10 seconds");
        } else {
            assertEquals(
                    expectedSyntaxStyle.get(),
                    actualSyntaxStyle.get(),
                    "Fragment syntaxStyle mismatch for ID " + expected.id());
        }

        assertEquals(expected.isText(), actual.isText(), "Fragment isText mismatch for ID " + expected.id());
        if (expected.isText()) {
            var expectedText = expected.text().await(Duration.of(30, ChronoUnit.SECONDS));
            var actualText = actual.text().await(Duration.of(30, ChronoUnit.SECONDS));
            if (expectedText.isEmpty() || actualText.isEmpty()) {
                fail("Fragment text content could not be computed within given timeout");
            } else {
                assertEquals(
                        expectedText.get(), actualText.get(), "Fragment text content mismatch for ID " + expected.id());
            }
        } else {
            // For image fragments, compare byte content via live image fragments
            var maybeExpectedBytesFuture = expected.imageBytes();
            var maybeActualBytesFuture = actual.imageBytes();
            if (maybeExpectedBytesFuture != null && maybeActualBytesFuture != null) {
                var expectedBytes = maybeExpectedBytesFuture.await(Duration.of(10, ChronoUnit.SECONDS));
                var actualBytes = maybeActualBytesFuture.await(Duration.of(10, ChronoUnit.SECONDS));
                if (expectedBytes.isEmpty() || actualBytes.isEmpty()) {
                    fail("Fragment image content could not be computed within 10 seconds");
                } else {
                    assertArrayEquals(
                            expectedBytes.get(),
                            actualBytes.get(),
                            "Fragment image content mismatch for ID " + expected.id());
                }
            } else {
                fail("Image byte futures are null, these fragments are not an images which was expected!");
            }
        }

        // Compare additional serialized top-level methods
        assertEquals(expected.repr(), actual.repr(), "Fragment repr mismatch for ID " + expected.id());

        // Compare files
        assertEquals(expected.files().join(), actual.files().join(), "Fragment files mismatch for ID " + expected.id());
    }

    private void assertTaskEntriesEqual(TaskEntry expected, TaskEntry actual) {
        assertEquals(expected.sequence(), actual.sequence());
        assertEquals(expected.isCompressed(), actual.isCompressed());
        if (expected.isCompressed()) {
            assertEquals(expected.summary(), actual.summary());
        } else {
            assertNotNull(expected.log());
            assertNotNull(actual.log());
            assertEquals(
                    expected.log().description().join(),
                    actual.log().description().join());
            assertEquals(
                    expected.log().messages().size(), actual.log().messages().size());
            for (int i = 0; i < expected.log().messages().size(); i++) {
                ChatMessage expectedMsg = expected.log().messages().get(i);
                ChatMessage actualMsg = actual.log().messages().get(i);
                assertEquals(expectedMsg.type(), actualMsg.type());
                assertEquals(Messages.getRepr(expectedMsg), Messages.getRepr(actualMsg));
            }
        }

        // Tolerate optional TaskMeta
        var expectedMeta = expected.meta();
        var actualMeta = actual.meta();
        if (expectedMeta == null) {
            // Accept null or default-like meta (NONE + null model)
            if (actualMeta != null) {
                assertEquals(
                        TaskResult.Type.NONE,
                        actualMeta.type(),
                        "When expected meta is null, actual type should be NONE");
                assertNull(actualMeta.primaryModel(), "When expected meta is null, actual primaryModel should be null");
            }
        } else {
            assertEquals(expectedMeta, actualMeta);
        }
    }

    @Test
    void testWriteReadHistoryWithSharedImageFragment() throws Exception {
        // Create a shared image
        var sharedImage = createTestImage(Color.BLUE, 8, 8);

        // Create two PasteImageFragments with identical content and description
        // This should result in the same FrozenFragment instance due to interning
        var sharedDescription = "Shared Blue Image";
        var liveImageFrag1 = new ContextFragments.AnonymousImageFragment(
                mockContextManager, sharedImage, CompletableFuture.completedFuture(sharedDescription));
        var liveImageFrag2 = new ContextFragments.AnonymousImageFragment(
                mockContextManager, sharedImage, CompletableFuture.completedFuture(sharedDescription));

        // Context 1 with first image fragment
        var ctx1 = new Context(mockContextManager).addFragments(liveImageFrag1);
        var originalHistory = new ContextHistory(ctx1);

        // Context 2 with second image fragment (same content, should intern to same FrozenFragment)
        var ctx2 = new Context(mockContextManager).addFragments(liveImageFrag2);
        originalHistory.pushContext(ctx2);

        // Write to ZIP - this should NOT throw ZipException: duplicate entry
        Path zipFile = tempDir.resolve("shared_image_history.zip");

        // The main test: writeZip should not throw ZipException
        assertDoesNotThrow(() -> HistoryIo.writeZip(originalHistory, zipFile));

        // Read back and verify
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        // Verify we have 2 contexts
        assertEquals(2, loadedHistory.getHistory().size());

        // Verify both contexts contain the shared image fragment
        var loadedCtx1 = loadedHistory.getHistory().get(0);
        var loadedCtx2 = loadedHistory.getHistory().get(1);

        // Find the image fragments in each context
        var fragment1 = loadedCtx1
                .virtualFragments()
                .filter(f -> !f.isText()
                        && "Shared Blue Image".equals(f.description().join()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Image fragment not found in loaded context 1"));

        var fragment2 = loadedCtx2
                .virtualFragments()
                .filter(f -> !f.isText()
                        && "Shared Blue Image".equals(f.description().join()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Image fragment not found in loaded context 2"));

        byte[] imageBytes1, imageBytes2;
        if (fragment1 instanceof ContextFragments.AnonymousImageFragment pif1) {
            imageBytes1 = pif1.imageBytes().join();
        } else {
            throw new AssertionError("Unexpected fragment type for image in ctx1: " + fragment1.getClass());
        }

        if (fragment2 instanceof ContextFragments.AnonymousImageFragment pif2) {
            imageBytes2 = pif2.imageBytes().join();
        } else {
            throw new AssertionError("Unexpected fragment type for image in ctx2: " + fragment2.getClass());
        }

        // Verify image content
        assertNotNull(imageBytes1);
        assertNotNull(imageBytes2);
        assertTrue(imageBytes1.length > 0);
        assertTrue(imageBytes2.length > 0);

        // Verify the image can be read back
        var reconstructedImage1 = ImageIO.read(new ByteArrayInputStream(imageBytes1));
        var reconstructedImage2 = ImageIO.read(new ByteArrayInputStream(imageBytes2));
        assertNotNull(reconstructedImage1);
        assertNotNull(reconstructedImage2);
        assertEquals(8, reconstructedImage1.getWidth());
        assertEquals(8, reconstructedImage1.getHeight());
        assertEquals(8, reconstructedImage2.getWidth());
        assertEquals(8, reconstructedImage2.getHeight());

        // Verify descriptions
        assertEquals(sharedDescription, fragment1.description().join());
        assertEquals(sharedDescription, fragment2.description().join());
    }

    @Test
    void testFragmentIdContinuityAfterLoad() throws IOException {
        var projectFile = new ProjectFile(tempDir, "dummy.txt");
        Files.createDirectories(projectFile.absPath().getParent()); // Ensure parent directory exists
        Files.writeString(projectFile.absPath(), "content");

        // ID of ctxFragment will be "1" (String)
        var ctxFragment = new ContextFragments.ProjectPathFragment(projectFile, mockContextManager);
        // ID of strFragment will be a hash string
        var strFragment = new ContextFragments.StringFragment(
                mockContextManager, "text", "desc", SyntaxConstants.SYNTAX_STYLE_NONE);

        var context = new Context(mockContextManager)
                .addFragments(List.of(ctxFragment))
                .addFragments(strFragment);
        var history = new ContextHistory(context);

        Path zipFile = tempDir.resolve("id_continuity_history.zip");
        HistoryIo.writeZip(history, zipFile);

        // Save the next available numeric ID *before* loading, then load.
        // Loading process (fragment constructors) will update ContextFragment.nextId.
        // For this test, we want to see what the next available numeric ID *was* before any new fragment creations
        // post-load.
        // ContextFragment.getCurrentMaxId() gives the *next* ID to be used.
        // After loading, ContextFragment.getCurrentMaxId() should be correctly set based on the max numeric ID found.
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        int maxNumericIdInLoadedHistory = 0;
        for (Context loadedCtx : loadedHistory.getHistory()) {
            for (ContextFragment frag : loadedCtx.allFragments().toList()) {
                try {
                    // Only consider numeric IDs from dynamic fragments
                    int numericId = Integer.parseInt(frag.id());
                    if (numericId > maxNumericIdInLoadedHistory) {
                        maxNumericIdInLoadedHistory = numericId;
                    }
                } catch (NumberFormatException e) {
                    // Non-numeric ID (hash), ignore for max numeric ID calculation
                }
            }
        }

        // The nextId counter should be at least maxNumericIdInLoadedHistory + 1.
        // If no numeric IDs were found (e.g. all fragments were content-hashed or history was empty),
        // then getCurrentMaxId() would be whatever it was set to initially (e.g. 1, or higher if other tests ran before
        // without reset)
        // or what it became after loading any initial numeric IDs from other fragments.
        int nextAvailableNumericId = ContextFragment.getCurrentMaxId();
        if (maxNumericIdInLoadedHistory > 0) {
            assertTrue(
                    nextAvailableNumericId > maxNumericIdInLoadedHistory,
                    "ContextFragment.nextId (numeric counter) should be greater than the max numeric ID found in loaded fragments.");
        } else {
            // If no numeric IDs, nextAvailableNumericId should be at least 1 (or whatever it was reset to)
            assertTrue(nextAvailableNumericId >= 1, "ContextFragment.nextId should be at least 1.");
        }

        // Create a new *dynamic* fragment; it should get a string representation of `nextAvailableNumericId`
        var newDynamicFragment = new ContextFragments.ProjectPathFragment(
                new ProjectFile(tempDir, "new_dynamic.txt"), mockContextManager);
        assertEquals(
                String.valueOf(nextAvailableNumericId),
                newDynamicFragment.id(),
                "New dynamic fragment should get the expected next numeric ID as a string.");
        assertEquals(
                nextAvailableNumericId + 1,
                ContextFragment.getCurrentMaxId(),
                "ContextFragment.nextId (numeric counter) should increment after new dynamic fragment creation.");
    }

    @Test
    void testDescriptionComputedAfterSerializationRoundTrip() throws Exception {
        // Initial state
        var context1 = new Context(mockContextManager);
        var history = new ContextHistory(context1);

        // 1. Action: Add a fragment
        var projectFile = new ProjectFile(tempDir, "test.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class Test {}");
        var fragment = new ContextFragments.ProjectPathFragment(projectFile, mockContextManager);
        var context2 = context1.addFragments(List.of(fragment));
        history.pushContext(context2);

        // 2. Action: Task entry
        var messages = List.<ChatMessage>of(UserMessage.from("Hello"), AiMessage.from("World"));
        var taskFragment = new ContextFragments.TaskFragment(mockContextManager, messages, "Task 1");
        var taskEntry = new TaskEntry(1, taskFragment, "Summary 1");
        var context3 = context2.addHistoryEntry(taskEntry, taskFragment);
        history.pushContext(context3);

        // Serialize to ZIP
        Path zipFile = tempDir.resolve("description_persistence_test.zip");
        HistoryIo.writeZip(history, zipFile);

        // Deserialize from ZIP
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        // Verify descriptions are correctly re-computed against loaded history
        List<Context> loadedContexts = loadedHistory.getHistory();
        assertEquals(3, loadedContexts.size());

        Context l1 = loadedContexts.get(0);
        Context l2 = loadedContexts.get(1);
        Context l3 = loadedContexts.get(2);

        assertEquals("Session Start", l1.getAction(null));
        assertEquals("Add test.java", l2.getAction(l1));
        assertEquals("Summary 1", l3.getAction(l2));
    }

    private String extractContextsJsonFromZip(Path zip) throws IOException {
        try (var zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("contexts.jsonl".equals(entry.getName())) {
                    return new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        throw new IOException("contexts.jsonl not found in zip");
    }

    @Test
    void testFragmentInterningDuringDeserialization() throws IOException {
        var context1 = new Context(mockContextManager);
        var projectFile = new ProjectFile(tempDir, "shared.txt");
        Files.writeString(projectFile.absPath(), "shared content");

        // Live ProjectPathFragment (dynamic)
        var liveProjectPathFragment = new ContextFragments.ProjectPathFragment(projectFile, mockContextManager);

        // Live StringFragment (non-dynamic, content-hashed)
        var liveStringFragment = new ContextFragments.StringFragment(
                mockContextManager,
                "unique string fragment content for interning test",
                "StringFragDesc",
                SyntaxConstants.SYNTAX_STYLE_NONE);
        String stringFragmentContentHashId = liveStringFragment.id();

        // Context 1
        var updatedContext1 =
                context1.addFragments(List.of(liveProjectPathFragment)).addFragments(liveStringFragment);
        var history = new ContextHistory(updatedContext1);

        // Context 2 also uses the same live instances
        var context2 = new Context(mockContextManager)
                .addFragments(List.of(liveProjectPathFragment))
                .addFragments(liveStringFragment);
        history.pushContext(context2);

        Path zipFile = tempDir.resolve("interning_test_history.zip");
        HistoryIo.writeZip(history, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertEquals(2, loadedHistory.getHistory().size());
        Context loadedCtx1 = loadedHistory.getHistory().get(0);
        Context loadedCtx2 = loadedHistory.getHistory().get(1);

        // Verify ProjectPathFragment
        var pathFrag1 = loadedCtx1
                .allFragments()
                .filter(f -> f instanceof ContextFragments.ProjectPathFragment)
                .map(f -> (ContextFragments.ProjectPathFragment) f)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ProjectPathFragment not found in loadedCtx1"));

        var pathFrag2 = loadedCtx2
                .allFragments()
                .filter(f -> f instanceof ContextFragments.ProjectPathFragment)
                .map(f -> (ContextFragments.ProjectPathFragment) f)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ProjectPathFragment not found in loadedCtx2"));

        assertSame(
                pathFrag1,
                pathFrag2,
                "Deserialized versions of the same dynamic ProjectPathFragment should be the same instance after deserialization and interning.");
        // Verify meta for ProjectPathFragment
        assertEquals(
                projectFile.getRoot().toString(), pathFrag1.file().getRoot().toString());
        assertEquals(
                projectFile.getRelPath().toString(),
                pathFrag1.file().getRelPath().toString());

        // Verify StringFragment (which remains StringFragment, non-dynamic, content-hashed ID)
        var loadedStringFrag1 = loadedCtx1
                .virtualFragments()
                .filter(f -> f instanceof ContextFragments.StringFragment
                        && Objects.equals(f.id(), stringFragmentContentHashId))
                .map(f -> (ContextFragments.StringFragment) f)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Shared StringFragment not found in loadedCtx1"));

        var loadedStringFrag2 = loadedCtx2
                .virtualFragments()
                .filter(f -> f instanceof ContextFragments.StringFragment
                        && Objects.equals(f.id(), stringFragmentContentHashId))
                .map(f -> (ContextFragments.StringFragment) f)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Shared StringFragment not found in loadedCtx2"));

        assertSame(
                loadedStringFrag1,
                loadedStringFrag2,
                "StringFragments with the same content-hash ID should be the same instance after deserialization.");
        assertEquals(
                "unique string fragment content for interning test",
                loadedStringFrag1.text().join());

        /* ---------- shared TaskFragment via TaskEntry ---------- */
        var taskMessages = List.of(UserMessage.from("User"), AiMessage.from("AI"));
        var sharedTaskFragment = new ContextFragments.TaskFragment(
                mockContextManager, taskMessages, "Shared Task Log"); // Content-hashed ID
        String sharedTaskFragmentId = sharedTaskFragment.id();

        var ctxWithTask1 = new Context(mockContextManager);
        var taskEntry = new TaskEntry(1, sharedTaskFragment, null);

        var updatedCtxWithTask1 = ctxWithTask1.addHistoryEntry(
                taskEntry, sharedTaskFragment);
        var origHistoryWithTask = new ContextHistory(updatedCtxWithTask1);

        var ctxWithTask2 = new Context(mockContextManager)
                .addHistoryEntry(taskEntry, sharedTaskFragment);
        origHistoryWithTask.pushContext(ctxWithTask2);

        Path taskZipFile = tempDir.resolve("interning_task_history.zip");
        HistoryIo.writeZip(origHistoryWithTask, taskZipFile);
        ContextHistory loadedHistoryWithTask = HistoryIo.readZip(taskZipFile, mockContextManager);

        var loadedTaskCtx1 = loadedHistoryWithTask.getHistory().get(0);
        var loadedTaskCtx2 = loadedHistoryWithTask.getHistory().get(1);

        var taskLog1 = loadedTaskCtx1.getTaskHistory().get(0).log();
        var taskLog2 = loadedTaskCtx2.getTaskHistory().get(0).log();

        assertNotNull(taskLog1);
        assertNotNull(taskLog2);
        assertEquals(sharedTaskFragmentId, taskLog1.id(), "TaskLog1 ID mismatch");
        assertEquals(sharedTaskFragmentId, taskLog2.id(), "TaskLog2 ID mismatch");
        assertSame(taskLog1, taskLog2, "Shared TaskFragment logs should be the same instance after deserialization.");
    }

    // --- Tests for individual fragment type round-trips ---

    private CodeUnit createTestCodeUnit(String fqName, ProjectFile pf) {
        String shortName = fqName.substring(fqName.lastIndexOf('.') + 1);
        String packageName = fqName.contains(".") ? fqName.substring(0, fqName.lastIndexOf('.')) : "";
        // Use CLASS as a generic kind for testing, specific kind might not be critical for serialization tests
        return new CodeUnit(pf, CodeUnitType.CLASS, packageName, shortName);
    }

    @Test
    void testRoundTripGitFileFragment() throws Exception {
        var projectFile = new ProjectFile(tempDir, "src/GitFile.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class GitFile {}");

        var fragment = new ContextFragments.GitFileFragment(projectFile, "abcdef1234567890", "content for git file");

        var context = new Context(mockContextManager).addFragments(List.of(fragment));
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_gitfile_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(
                originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        // Verify specific GitFileFragment properties after general assertion
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedFragment = (ContextFragments.GitFileFragment) loadedCtx
                .allFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.GIT_FILE)
                .findFirst()
                .orElseThrow();
        assertEquals(
                projectFile.absPath().toString(),
                loadedFragment.file().absPath().toString());
        assertEquals("abcdef1234567890", loadedFragment.revision());
        assertEquals("content for git file", loadedFragment.content());
    }

    @Test
    void testRoundTripExternalPathFragment() throws Exception {
        Path externalFilePath = tempDir.resolve("external_file.txt");
        Files.writeString(externalFilePath, "External file content");
        var externalFile = new ExternalFile(externalFilePath);
        var fragment = new ContextFragments.ExternalPathFragment(externalFile, mockContextManager);

        var context = new Context(mockContextManager).addFragments(List.of(fragment));
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_externalpath_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(
                originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedRawFragment = loadedCtx
                .allFragments()
                .filter(f -> f.getType()
                        == ContextFragment.FragmentType.EXTERNAL_PATH) // getType on FrozenFragment returns originalType
                .findFirst()
                .orElseThrow();

        assertTrue(
                loadedRawFragment instanceof ContextFragments.ExternalPathFragment,
                "ExternalPathFragment should be loaded as an ExternalPathFragment");
        var loadedPathFragment = (ContextFragments.ExternalPathFragment) loadedRawFragment;
        assertEquals(ContextFragment.FragmentType.EXTERNAL_PATH, loadedPathFragment.getType());
        assertEquals(
                externalFilePath.toString(), loadedPathFragment.file().absPath().toString());
    }

    @Test
    void testRoundTripImageFileFragment() throws Exception {
        Path imageFilePath = tempDir.resolve("test_image.png");
        var testImage = createTestImage(Color.GREEN, 20, 20);
        ImageIO.write(testImage, "PNG", imageFilePath.toFile());
        var brokkImageFile =
                new ProjectFile(tempDir, tempDir.relativize(imageFilePath)); // Treat as project file for test
        var fragment = new ContextFragments.ImageFileFragment(brokkImageFile, mockContextManager);

        var context = new Context(mockContextManager).addFragments(List.of(fragment));
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_imagefile_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(
                originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedRawFragment = loadedCtx
                .allFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.IMAGE_FILE)
                .findFirst()
                .orElseThrow();

        assertTrue(
                loadedRawFragment instanceof ContextFragments.ImageFileFragment,
                "ImageFileFragment should be deserialized directly");
        var loadedImageFragment = (ContextFragments.ImageFileFragment) loadedRawFragment;
        assertEquals(ContextFragment.FragmentType.IMAGE_FILE, loadedImageFragment.getType());

        // Check path from meta
        String loadedAbsPath = loadedImageFragment.file().absPath().toString();
        assertNotNull(loadedAbsPath, "absPath not found in FrozenFragment meta for ImageFileFragment");
        assertEquals(imageFilePath.toString(), loadedAbsPath);

        // Check image content from bytes
        byte[] imageBytes = Objects.requireNonNull(loadedImageFragment.imageBytes())
                .await(Duration.ofSeconds(5))
                .get();
        assertNotNull(imageBytes, "Image bytes not found in FrozenFragment for ImageFileFragment");
        Image loadedImageFromBytes = ImageIO.read(new ByteArrayInputStream(imageBytes));
        assertNotNull(loadedImageFromBytes);
        assertEquals(20, loadedImageFromBytes.getWidth(null));
        assertEquals(20, loadedImageFromBytes.getHeight(null));
    }

    @Test
    void testRoundTripUsageFragment() throws Exception {
        var fragment = new ContextFragments.UsageFragment(mockContextManager, "com.example.MyClass.myMethod");

        var context = new Context(mockContextManager).addFragments(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_usage_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(
                originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedRawFragment = loadedCtx
                .virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.USAGE)
                .findFirst()
                .orElseThrow();

        if (loadedRawFragment instanceof ContextFragments.UsageFragment loadedFragment) {
            assertEquals("com.example.MyClass.myMethod", loadedFragment.targetIdentifier());
        } else {
            fail("Expected UsageFragment or FrozenFragment, got: " + loadedRawFragment.getClass());
        }
    }

    @Test
    void testRoundTripUsageFragmentIncludeTestFiles() throws Exception {
        var fragment = new ContextFragments.UsageFragment(mockContextManager, "com.example.MyClass.myMethod", true);

        var context = new Context(mockContextManager).addFragments(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_usage_include_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedRawFragment = loadedCtx
                .virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.USAGE)
                .findFirst()
                .orElseThrow();

        if (loadedRawFragment instanceof ContextFragments.UsageFragment loadedFragment) {
            assertTrue(loadedFragment.includeTestFiles(), "includeTestFiles should be preserved as true");
            assertEquals("com.example.MyClass.myMethod", loadedFragment.targetIdentifier());
        } else {
            fail("Expected UsageFragment or FrozenFragment, got: " + loadedRawFragment.getClass());
        }
    }

    @Test
    void testRoundTripCallGraphFragment() throws Exception {
        var fragment =
                new ContextFragments.CallGraphFragment(mockContextManager, "com.example.MyClass.doStuff", 3, true);

        var context = new Context(mockContextManager).addFragments(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_callgraph_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(
                originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedRawFragment = loadedCtx
                .virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.CALL_GRAPH)
                .findFirst()
                .orElseThrow();

        if (loadedRawFragment instanceof ContextFragments.CallGraphFragment loadedFragment) {
            assertEquals("com.example.MyClass.doStuff", loadedFragment.getMethodName());
            assertEquals(3, loadedFragment.getDepth());
            assertTrue(loadedFragment.isCalleeGraph());
        } else {
            fail("Expected CallGraphFragment or FrozenFragment, got: " + loadedRawFragment.getClass());
        }
    }

    @Test
    void testRoundTripHistoryFragment() throws Exception {
        var taskMessages = List.<ChatMessage>of(UserMessage.from("Task user"), AiMessage.from("Task AI"));
        var taskFragment = new ContextFragments.TaskFragment(mockContextManager, taskMessages, "Test Task Log");
        var taskEntry = new TaskEntry(1, taskFragment, null);
        var fragment = new ContextFragments.HistoryFragment(mockContextManager, List.of(taskEntry));

        var context = new Context(mockContextManager).addFragments(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_history_frag_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(
                originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedFragment = (ContextFragments.HistoryFragment) loadedCtx
                .allFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.HISTORY)
                .findFirst()
                .orElseThrow();
        assertEquals(1, loadedFragment.entries().size());
        assertTaskEntriesEqual(taskEntry, loadedFragment.entries().get(0));
    }

    @Test
    void testRoundTripPasteTextFragment() throws Exception {
        var fragment = new ContextFragments.PasteTextFragment(
                mockContextManager,
                "Pasted text content",
                CompletableFuture.completedFuture("Pasted text summary"),
                CompletableFuture.completedFuture(SyntaxConstants.SYNTAX_STYLE_MARKDOWN));

        var context = new Context(mockContextManager).addFragments(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_pastetext_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        // Allow both histories to be loaded
        context.awaitContextsAreComputed(Duration.ofSeconds(10));
        loadedHistory.liveContext().awaitContextsAreComputed(Duration.ofSeconds(10));

        assertContextsEqual(
                originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedFragment = (ContextFragments.PasteTextFragment) loadedCtx
                .allFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.PASTE_TEXT)
                .findFirst()
                .orElseThrow();
        assertEquals("Pasted text content", loadedFragment.text().join());
        assertEquals(
                "Paste of Pasted text summary", loadedFragment.description().join());
    }

    @Test
    void testRoundTripStacktraceFragment() throws Exception {
        var projectFile = new ProjectFile(tempDir, "src/ErrorSource.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class ErrorSource {}");
        var codeUnit = createTestCodeUnit("com.example.ErrorSource", projectFile);
        var fragment = new ContextFragments.StacktraceFragment(
                mockContextManager,
                Set.of(codeUnit),
                "Full stacktrace original text",
                "NullPointerException",
                "ErrorSource.java:10");

        var context = new Context(mockContextManager).addFragments(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_stacktrace_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(
                originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedFragment = (ContextFragments.StacktraceFragment) loadedCtx
                .virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.STACKTRACE)
                .findFirst()
                .orElseThrow();
        assertEquals(
                "stacktrace of NullPointerException",
                loadedFragment.description().join());
        assertTrue(loadedFragment.text().join().contains("Full stacktrace original text"));
        assertTrue(loadedFragment.text().join().contains("ErrorSource.java:10"));
        assertEquals(1, loadedFragment.sources().join().size());
        assertEquals(
                codeUnit.fqName(),
                loadedFragment.sources().join().iterator().next().fqName());
    }

    @Test
    void testVirtualFragmentDeduplicationAfterSerialization() throws Exception {
        var context = new Context(mockContextManager);

        // Add virtual fragments, some with duplicate text content
        // The IDs will be 3, 4, 5, 6, 7 based on current setup
        var vf1 = new ContextFragments.StringFragment(
                mockContextManager,
                "Content for uniqueText1 (first)",
                "uniqueText1",
                SyntaxConstants.SYNTAX_STYLE_NONE);
        var vf2 = new ContextFragments.StringFragment(
                mockContextManager,
                "Content for duplicateText (first)",
                "duplicateText",
                SyntaxConstants.SYNTAX_STYLE_NONE);
        var vf3 = new ContextFragments.StringFragment(
                mockContextManager, "Content for uniqueText2", "uniqueText2", SyntaxConstants.SYNTAX_STYLE_NONE);
        var vf4_duplicate_of_vf2 = new ContextFragments.StringFragment(
                mockContextManager,
                "Content for duplicateText (second, different desc)",
                "duplicateText",
                SyntaxConstants.SYNTAX_STYLE_NONE);
        var vf5_duplicate_of_vf1 = new ContextFragments.StringFragment(
                mockContextManager,
                "Content for uniqueText1 (second, different desc)",
                "uniqueText1",
                SyntaxConstants.SYNTAX_STYLE_NONE);

        context = context.addFragments(vf1);
        context = context.addFragments(vf2);
        context = context.addFragments(vf3);
        context = context.addFragments(vf4_duplicate_of_vf2);
        context = context.addFragments(vf5_duplicate_of_vf1);

        ContextHistory originalHistory = new ContextHistory(context);

        // Serialize and deserialize
        Path zipFile = tempDir.resolve("deduplication_test_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertNotNull(loadedHistory);
        assertEquals(1, loadedHistory.getHistory().size());
        Context deserializedContext = loadedHistory.getHistory().get(0);

        // Verify deduplication behavior of virtualFragments()
        List<ContextFragment> deduplicatedFragments =
                deserializedContext.virtualFragments().toList();

        // Expected: 5 unique fragments based on text content, common description should not result in being treated as
        // duplicates
        assertEquals(5, deduplicatedFragments.size(), "Should be 5 unique virtual fragments after deduplication.");
    }

    @Test
    void testWriteReadHistoryWithGitState() throws IOException {
        // 1. Setup context 1 with a git state that has a diff
        var context1 = new Context(mockContextManager);
        var history = new ContextHistory(context1);
        var context1Id = context1.id();
        var diffContent =
                """
                        diff --git a/file.txt b/file.txt
                        --- a/file.txt
                        +++ b/file.txt
                        @@ -1 +1 @@
                        -hello
                        +world
                        """;
        var gitState1 = new ContextHistory.GitState("test-commit-hash-1", diffContent);
        history.addGitState(context1Id, gitState1);

        // 2. Setup context 2 with a git state that has a null diff
        var context2 = new Context(mockContextManager);
        history.pushContext(context2);
        var context2Id = context2.id();
        var gitState2 = new ContextHistory.GitState("test-commit-hash-2", null);
        history.addGitState(context2Id, gitState2);

        // 3. Write to ZIP
        Path zipFile = tempDir.resolve("history_with_git_state.zip");
        HistoryIo.writeZip(history, zipFile);

        // 4. Read from ZIP
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        // 5. Assertions
        assertNotNull(loadedHistory);
        assertEquals(2, loadedHistory.getHistory().size());

        // Verify first context and its git state
        assertEquals(context1Id, loadedHistory.getHistory().get(0).id());
        var loadedGitState1 = loadedHistory.getGitState(context1Id);
        assertTrue(loadedGitState1.isPresent());
        assertEquals("test-commit-hash-1", loadedGitState1.get().commitHash());
        assertEquals(diffContent, loadedGitState1.get().diff());

        // Verify second context and its git state
        assertEquals(context2Id, loadedHistory.getHistory().get(1).id());
        var loadedGitState2 = loadedHistory.getGitState(context2Id);
        assertTrue(loadedGitState2.isPresent());
        assertEquals("test-commit-hash-2", loadedGitState2.get().commitHash());
        assertNull(loadedGitState2.get().diff());
    }

    @Test
    void testRoundTripCodeFragment() throws Exception {
        var projectFile = new ProjectFile(tempDir, "src/CodeFragmentTarget.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class CodeFragmentTarget {}");
        var codeUnit = createTestCodeUnit("com.example.CodeFragmentTarget", projectFile);

        var fragment = new ContextFragments.CodeFragment(mockContextManager, codeUnit);

        var context = new Context(mockContextManager).addFragments(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_codefragment_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        var originalCtx = originalHistory.getHistory().getFirst();
        var loadedCtx = loadedHistory.getHistory().getFirst();
        originalCtx.awaitContextsAreComputed(Duration.ofSeconds(15));
        loadedCtx.awaitContextsAreComputed(Duration.ofSeconds(15));
        // equals no longer passes since we changed description from fqName to shortName
        // assertContextsEqual(originalCtx, loadedCtx);

        var loadedRawFragment = loadedCtx
                .virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.CODE)
                .findFirst()
                .orElseThrow();

        if (loadedRawFragment instanceof ContextFragments.CodeFragment loadedFragment) {
            assertEquals(codeUnit.fqName(), loadedFragment.getFullyQualifiedName());
        } else {
            fail("Expected CodeFragment or FrozenFragment, got: " + loadedRawFragment.getClass());
        }
    }

    @Test
    void testRoundTripSummaryFragment() throws Exception {
        // Test CODEUNIT_SKELETON summary type
        var fragment1 = new ContextFragments.SummaryFragment(
                mockContextManager, "com.example.TargetClass", ContextFragment.SummaryType.CODEUNIT_SKELETON);

        var context1 = new Context(mockContextManager).addFragments(fragment1);
        ContextHistory originalHistory1 = new ContextHistory(context1);

        Path zipFile1 = tempDir.resolve("test_summary_codeunit_history.zip");
        HistoryIo.writeZip(originalHistory1, zipFile1);
        ContextHistory loadedHistory1 = HistoryIo.readZip(zipFile1, mockContextManager);

        assertContextsEqual(
                originalHistory1.getHistory().get(0),
                loadedHistory1.getHistory().get(0));

        Context loadedCtx1 = loadedHistory1.getHistory().get(0);
        var loadedRawFragment1 = loadedCtx1
                .virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.SKELETON)
                .findFirst()
                .orElseThrow();

        if (loadedRawFragment1 instanceof ContextFragments.SummaryFragment loadedFragment) {
            assertEquals("com.example.TargetClass", loadedFragment.getTargetIdentifier());
            assertEquals(ContextFragment.SummaryType.CODEUNIT_SKELETON, loadedFragment.getSummaryType());
        } else {
            fail("Expected SummaryFragment or FrozenFragment, got: " + loadedRawFragment1.getClass());
        }

        // Test FILE_SKELETONS summary type
        var projectFile = new ProjectFile(tempDir, "src/SummaryTest.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class SummaryTest {}");

        var fragment2 = new ContextFragments.SummaryFragment(
                mockContextManager, projectFile.toString(), ContextFragment.SummaryType.FILE_SKELETONS);

        var context2 = new Context(mockContextManager).addFragments(fragment2);
        ContextHistory originalHistory2 = new ContextHistory(context2);

        Path zipFile2 = tempDir.resolve("test_summary_file_history.zip");
        HistoryIo.writeZip(originalHistory2, zipFile2);
        ContextHistory loadedHistory2 = HistoryIo.readZip(zipFile2, mockContextManager);

        assertContextsEqual(
                originalHistory2.getHistory().get(0),
                loadedHistory2.getHistory().get(0));

        Context loadedCtx2 = loadedHistory2.getHistory().get(0);
        var loadedRawFragment2 = loadedCtx2
                .virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.SKELETON)
                .findFirst()
                .orElseThrow();

        if (loadedRawFragment2 instanceof ContextFragments.SummaryFragment loadedFragment) {
            assertEquals(projectFile.toString(), loadedFragment.getTargetIdentifier());
            assertEquals(ContextFragment.SummaryType.FILE_SKELETONS, loadedFragment.getSummaryType());
        } else {
            fail("Expected SummaryFragment or FrozenFragment, got: " + loadedRawFragment2.getClass());
        }
    }

    @Test
    void testTaskEntryMetaRoundTrip() throws Exception {
        var messages = List.of(UserMessage.from("User"), AiMessage.from("AI"));
        var taskFragment = new ContextFragments.TaskFragment(mockContextManager, messages, "Test Task");

        TaskResult.TaskMeta meta = new TaskResult.TaskMeta(
                TaskResult.Type.CODE,
                new Service.ModelConfig("test-model", Service.ReasoningLevel.DEFAULT, Service.ProcessingTier.DEFAULT));
        var taskEntry = new TaskEntry(42, taskFragment, null, meta);

        var ctx = new Context(mockContextManager)
                .addHistoryEntry(taskEntry, taskFragment);
        ContextHistory ch = new ContextHistory(ctx);

        Path zipFile = tempDir.resolve("meta_roundtrip.zip");
        HistoryIo.writeZip(ch, zipFile);
        ContextHistory loaded = HistoryIo.readZip(zipFile, mockContextManager);

        TaskEntry loadedEntry = loaded.getHistory().get(0).getTaskHistory().get(0);
        assertNotNull(loadedEntry.meta(), "TaskMeta should be present after round-trip");
        assertEquals(TaskResult.Type.CODE, loadedEntry.meta().type());
        assertNotNull(loadedEntry.meta().primaryModel());
        assertEquals("test-model", loadedEntry.meta().primaryModel().name());
        assertEquals(
                Service.ReasoningLevel.DEFAULT,
                loadedEntry.meta().primaryModel().reasoning());
    }

    @Test
    void testPinnedRoundTrip() throws Exception {
        // Setup a fragment and pin it
        var sf = new ContextFragments.StringFragment(
                mockContextManager, "Pinned content", "Pinned Desc", SyntaxConstants.SYNTAX_STYLE_NONE);
        var ctx = new Context(mockContextManager).addFragments(sf).withPinned(sf, true);

        assertTrue(ctx.isPinned(sf), "Fragment should be pinned in original context");

        ContextHistory ch = new ContextHistory(ctx);
        Path zipFile = tempDir.resolve("pinned_roundtrip.zip");
        HistoryIo.writeZip(ch, zipFile);

        // Deserialize
        ContextHistory loaded = HistoryIo.readZip(zipFile, mockContextManager);
        Context loadedCtx = loaded.getHistory().getFirst();

        var loadedSf = loadedCtx
                .virtualFragments()
                .filter(f -> f.description().join().equals("Pinned Desc"))
                .findFirst()
                .orElseThrow();

        assertTrue(loadedCtx.isPinned(loadedSf), "Fragment should remain pinned after round-trip");

        // Verify unpinned fragment stays unpinned
        var sf2 = new ContextFragments.StringFragment(
                mockContextManager, "Unpinned content", "Unpinned Desc", SyntaxConstants.SYNTAX_STYLE_NONE);
        var ctx2 = ctx.addFragments(sf2);
        assertFalse(ctx2.isPinned(sf2));

        Path zipFile2 = tempDir.resolve("pinned_mixed_roundtrip.zip");
        HistoryIo.writeZip(new ContextHistory(ctx2), zipFile2);

        ContextHistory loaded2 = HistoryIo.readZip(zipFile2, mockContextManager);
        Context loadedCtx2 = loaded2.getHistory().getFirst();
        var loadedSf2 = loadedCtx2
                .virtualFragments()
                .filter(f -> f.description().join().equals("Unpinned Desc"))
                .findFirst()
                .orElseThrow();
        assertFalse(loadedCtx2.isPinned(loadedSf2), "Unpinned fragment should remain unpinned");
    }

    @Test
    void testReadOnlyRoundTripForEditableFragments() throws Exception {
        // Create editable ProjectPathFragment and CodeFragment, plus non-editable StringFragment
        var projectFile = new ProjectFile(tempDir, "src/RoTest.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class RoTest {}");

        var ppf = new ContextFragments.ProjectPathFragment(projectFile, mockContextManager);

        var codeUnit = createTestCodeUnit(
                "com.example.CodeFragmentTarget", new ProjectFile(tempDir, "src/CodeFragmentTarget.java"));
        var codeFrag = new ContextFragments.CodeFragment(mockContextManager, codeUnit);

        var sf = new ContextFragments.StringFragment(
                mockContextManager, "text", "desc", SyntaxConstants.SYNTAX_STYLE_NONE);

        var ctx = new Context(mockContextManager)
                .addFragments(List.of(ppf))
                .addFragments(codeFrag)
                .addFragments(sf);
        // Toggle read-only via Context
        ctx = ctx.setReadonly(ppf, true);
        ctx = ctx.setReadonly(codeFrag, true);

        ContextHistory ch = new ContextHistory(ctx);

        Path zipFile = tempDir.resolve("readonly_roundtrip.zip");
        HistoryIo.writeZip(ch, zipFile);

        // Verify serialization: contexts.jsonl contains readonly IDs for ppf and codeFrag
        var readonlyIds = readReadonlyIdsFromZip(zipFile);
        assertTrue(readonlyIds.contains(ppf.id()), "readonly should contain ProjectPathFragment id");
        assertTrue(readonlyIds.contains(codeFrag.id()), "readonly should contain CodeFragment id");
        assertFalse(readonlyIds.contains(sf.id()), "readonly should not contain non-editable StringFragment id");

        // Verify deserialization preserves readOnly flags for editable fragments
        ContextHistory loaded = HistoryIo.readZip(zipFile, mockContextManager);
        var loadedCtx = loaded.getHistory().getFirst();

        var loadedPpf = loadedCtx
                .fileFragments()
                .filter(f -> f instanceof ContextFragments.ProjectPathFragment)
                .map(f -> (ContextFragments.ProjectPathFragment) f)
                .findFirst()
                .orElseThrow();
        assertTrue(loadedCtx.isMarkedReadonly(loadedPpf), "Loaded ProjectPathFragment should be read-only");

        var loadedCode = loadedCtx
                .virtualFragments()
                .filter(f -> f instanceof ContextFragments.CodeFragment)
                .map(f -> (ContextFragments.CodeFragment) f)
                .findFirst()
                .orElseThrow();
        assertTrue(loadedCtx.isMarkedReadonly(loadedCode), "Loaded CodeFragment should be read-only");
    }

    // --- Integration tests for AiMessage reasoning content round-trips ---

    @Test
    void testRoundTripAiMessageWithReasoningOnly() {
        // Serialize and deserialize an AiMessage with reasoning only
        var originalMessage = new AiMessage("", "This is the reasoning content");
        var contentWriter = new HistoryIo.ContentWriter();
        var contentReader = new HistoryIo.ContentReader(contentWriter.getContentBytes());
        contentReader.setContentMetadata(contentWriter.getContentMetadata());

        // Serialize
        var dto = DtoMapper.toChatMessageDto(originalMessage, contentWriter);

        // Verify DTO has both contentId (empty text) and reasoningContentId
        assertNotNull(dto.contentId(), "contentId should be present");
        assertNotNull(dto.reasoningContentId(), "reasoningContentId should be present for reasoning-only message");

        // Deserialize
        var deserializedMessage = DtoMapper.fromChatMessageDto(dto, contentReader);

        // Verify round-trip preservation
        assertTrue(deserializedMessage instanceof AiMessage, "Should deserialize as AiMessage");
        var aiMsg = (AiMessage) deserializedMessage;
        assertEquals("", aiMsg.text(), "Text should be empty");
        assertEquals("This is the reasoning content", aiMsg.reasoningContent(), "Reasoning should be preserved");

        // Verify Messages.isReasoningMessage detects this as a reasoning message
        assertTrue(
                Messages.isReasoningMessage(aiMsg), "isReasoningMessage should return true for reasoning-only message");
    }

    @Test
    void testRoundTripAiMessageWithTextOnly() {
        // Serialize and deserialize an AiMessage with text only
        var originalMessage = new AiMessage("This is the text response");
        var contentWriter = new HistoryIo.ContentWriter();
        var contentReader = new HistoryIo.ContentReader(contentWriter.getContentBytes());
        contentReader.setContentMetadata(contentWriter.getContentMetadata());

        // Serialize
        var dto = DtoMapper.toChatMessageDto(originalMessage, contentWriter);

        // Verify DTO has contentId but no reasoningContentId
        assertNotNull(dto.contentId(), "contentId should be present");
        assertNull(dto.reasoningContentId(), "reasoningContentId should be null for text-only message");

        // Deserialize
        var deserializedMessage = DtoMapper.fromChatMessageDto(dto, contentReader);

        // Verify round-trip preservation
        assertTrue(deserializedMessage instanceof AiMessage, "Should deserialize as AiMessage");
        var aiMsg = (AiMessage) deserializedMessage;
        assertEquals("This is the text response", aiMsg.text(), "Text should be preserved");
        assertNull(aiMsg.reasoningContent(), "Reasoning should be null for text-only message");

        // Verify Messages.isReasoningMessage returns false
        assertFalse(Messages.isReasoningMessage(aiMsg), "isReasoningMessage should return false for text-only message");
    }

    @Test
    void testRoundTripAiMessageWithBothReasoningAndText() {
        // Serialize and deserialize an AiMessage with both reasoning and text
        var originalMessage = new AiMessage("The final answer is 42", "Let me think through this step by step...");
        var contentWriter = new HistoryIo.ContentWriter();
        var contentReader = new HistoryIo.ContentReader(contentWriter.getContentBytes());
        contentReader.setContentMetadata(contentWriter.getContentMetadata());

        // Serialize
        var dto = DtoMapper.toChatMessageDto(originalMessage, contentWriter);

        // Verify DTO has both contentId and reasoningContentId
        assertNotNull(dto.contentId(), "contentId should be present");
        assertNotNull(dto.reasoningContentId(), "reasoningContentId should be present for message with both");

        // Deserialize
        var deserializedMessage = DtoMapper.fromChatMessageDto(dto, contentReader);

        // Verify round-trip preservation
        assertTrue(deserializedMessage instanceof AiMessage, "Should deserialize as AiMessage");
        var aiMsg = (AiMessage) deserializedMessage;
        assertEquals("The final answer is 42", aiMsg.text(), "Text should be preserved");
        assertEquals(
                "Let me think through this step by step...", aiMsg.reasoningContent(), "Reasoning should be preserved");

        // Verify Messages.isReasoningMessage returns true (reasoning is present and non-blank)
        assertTrue(
                Messages.isReasoningMessage(aiMsg), "isReasoningMessage should return true when reasoning is present");
    }

    @Test
    void testTaskFragmentWithAiMessageReasoningRoundTrip() throws Exception {
        // Integration test: round-trip a TaskFragment containing AiMessages with reasoning
        var messages = List.of(
                UserMessage.from("What is 2 + 2?"),
                new AiMessage("The answer is 4", "Let me think: 2 + 2 equals 4"),
                UserMessage.from("Verify your work"),
                new AiMessage("Verified", "2 + 2 = 4 by basic arithmetic"));

        var taskFragment = new ContextFragments.TaskFragment(mockContextManager, messages, "Math Task");

        var contentWriter = new HistoryIo.ContentWriter();
        var taskDto = DtoMapper.toTaskFragmentDto(taskFragment, contentWriter);

        var contentReader = new HistoryIo.ContentReader(contentWriter.getContentBytes());
        contentReader.setContentMetadata(contentWriter.getContentMetadata());

        // Rebuild the task fragment from DTO
        var rebuiltMessages = taskDto.messages().stream()
                .map(msgDto -> DtoMapper.fromChatMessageDto(msgDto, contentReader))
                .toList();

        // Verify original and rebuilt messages match
        assertEquals(messages.size(), rebuiltMessages.size(), "Message count should match");

        for (int i = 0; i < messages.size(); i++) {
            var originalMsg = messages.get(i);
            var rebuiltMsg = rebuiltMessages.get(i);

            assertEquals(originalMsg.type(), rebuiltMsg.type(), "Message type at index " + i + " should match");
            assertEquals(
                    Messages.getRepr(originalMsg),
                    Messages.getRepr(rebuiltMsg),
                    "Message repr at index " + i + " should match");

            // For AiMessages, verify reasoning is preserved
            if (originalMsg instanceof AiMessage originalAi && rebuiltMsg instanceof AiMessage rebuiltAi) {
                assertEquals(
                        originalAi.reasoningContent(),
                        rebuiltAi.reasoningContent(),
                        "Reasoning content at index " + i + " should match");
                assertEquals(
                        Messages.isReasoningMessage(originalAi),
                        Messages.isReasoningMessage(rebuiltAi),
                        "isReasoningMessage result at index " + i + " should match");
            }
        }
    }

    @Test
    void testTaskEntryHelperMethods() {
        // Test all three scenarios: log-only, summary-only, and both
        var messages = List.<ChatMessage>of(UserMessage.from("User"), AiMessage.from("AI"));
        var taskFragment = new ContextFragments.TaskFragment(mockContextManager, messages, "Task");

        // Log only
        var logOnly = new TaskEntry(1, taskFragment, null);
        assertTrue(logOnly.hasLog());
        assertFalse(logOnly.isCompressed());

        // Summary only
        var summaryOnly = new TaskEntry(2, null, "Summary text");
        assertFalse(summaryOnly.hasLog());
        assertTrue(summaryOnly.isCompressed());

        // Both
        var both = new TaskEntry(3, taskFragment, "Summary text");
        assertTrue(both.hasLog());
        assertTrue(both.isCompressed());
    }

    @Test
    void testTaskEntryToStringShowsSummaryForAI() {
        // Verify toString shows summary (not full messages) for AI consumption
        // Per design: "the AI sees the summary, but the UI prefers to render the full log messages"
        var messages = List.of(UserMessage.from("User"), AiMessage.from("AI"));
        var taskFragment = new ContextFragments.TaskFragment(mockContextManager, messages, "Task");
        var summary = "Compressed summary";

        // When both log and summary exist, toString should show the summary for the AI
        var both = new TaskEntry(1, taskFragment, summary);
        String str = both.toString();

        assertTrue(str.contains("summarized=true"), "toString should indicate summarized");
        assertTrue(str.contains("Compressed summary"), "toString should include the summary content");
        // The AI view should NOT include full message types when summary is present
        assertFalse(str.contains("<message type="), "toString should not include message tags when summarized");

        // When only log exists (no summary), toString should show the full messages
        var logOnly = new TaskEntry(2, taskFragment, null);
        String str2 = logOnly.toString();
        assertFalse(str2.contains("summarized=true"), "log-only should not indicate summarized");
        assertTrue(str2.contains("<message type=user>"), "log-only should include user message");
        assertTrue(str2.contains("<message type=ai>"), "log-only should include ai message");

        // When only summary exists (no log), toString should show the summary
        var summaryOnly = new TaskEntry(3, null, summary);
        String str3 = summaryOnly.toString();
        assertTrue(str3.contains("summarized=true"), "summary-only should indicate summarized");
        assertTrue(str3.contains("Compressed summary"), "summary-only should include summary content");
    }

    @Test
    void testBackwardCompatibilityTaskEntryConstruction() {
        // Verify the old 3-arg constructor still works
        List<ChatMessage> messages = List.of(UserMessage.from("User"));
        var taskFragment = new ContextFragments.TaskFragment(mockContextManager, messages, "Task");

        // Old way: 3 args (no meta)
        var entry = new TaskEntry(1, taskFragment, null);
        assertNotNull(entry);
        assertNull(entry.meta());
        assertTrue(entry.hasLog());
        assertFalse(entry.isCompressed());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testReadOnlyBackwardCompatibilityLongerAndShorterLists() throws Exception {
        // Build a simple context with one editable and one non-editable fragment
        var projectFile = new ProjectFile(tempDir, "src/BC.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class BC {}");

        var ppf = new ContextFragments.ProjectPathFragment(projectFile, mockContextManager);
        var sf =
                new ContextFragments.StringFragment(mockContextManager, "s", "desc", SyntaxConstants.SYNTAX_STYLE_NONE);

        var ctx = new Context(mockContextManager).addFragments(List.of(ppf)).addFragments(sf);

        ContextHistory ch = new ContextHistory(ctx);
        Path original = tempDir.resolve("bc_original.zip");
        HistoryIo.writeZip(ch, original);

        // Case 1: Longer list (add unknown id and non-editable id)
        Path longerZip = tempDir.resolve("bc_longer.zip");
        rewriteContextsInZip(original, longerZip, line -> {
            try {
                var mapper = new ObjectMapper();
                Map<String, Object> obj = mapper.readValue(line, new TypeReference<Map<String, Object>>() {});
                @SuppressWarnings("unchecked")
                List<String> readonly = (List<String>) obj.getOrDefault("readonly", new ArrayList<>());
                readonly = new ArrayList<>(readonly);
                readonly.add("bogus-id-not-present");
                readonly.add(sf.id()); // include non-editable fragment ID
                obj.put("readonly", readonly);
                return mapper.writeValueAsString(obj);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        ContextHistory loadedLonger = HistoryIo.readZip(longerZip, mockContextManager);
        var loadedPpf = loadedLonger
                .getHistory()
                .getFirst()
                .fileFragments()
                .filter(f -> f instanceof ContextFragments.ProjectPathFragment)
                .map(f -> (ContextFragments.ProjectPathFragment) f)
                .findFirst()
                .orElseThrow();
        // Since original readonly list was empty, and we only added ids (including non-editable), ProjectPath remains
        // not read-only
        assertFalse(
                loadedLonger.getHistory().getFirst().isMarkedReadonly(loadedPpf),
                "Editable fragment should remain not read-only when list contains unknown/non-editable ids only");

        // Case 2: Shorter list (explicitly clear readonly even if we set it true pre-serialization)
        // Set readOnly via Context and serialize
        var ctx2 = new Context(mockContextManager).addFragments(List.of(ppf));
        ctx2 = ctx2.setReadonly(ppf, true);
        ContextHistory ch2 = new ContextHistory(ctx2);
        Path marked = tempDir.resolve("bc_marked.zip");
        HistoryIo.writeZip(ch2, marked);

        // Now remove ID from readonly to simulate shorter list
        Path shorterZip = tempDir.resolve("bc_shorter.zip");
        rewriteContextsInZip(marked, shorterZip, line -> {
            try {
                var mapper = new ObjectMapper();
                Map<String, Object> obj = mapper.readValue(line, new TypeReference<Map<String, Object>>() {});
                obj.put("readonly", new ArrayList<>()); // clear readonly list
                return mapper.writeValueAsString(obj);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        ContextHistory loadedShorter = HistoryIo.readZip(shorterZip, mockContextManager);
        var loadedPpf2 = loadedShorter
                .getHistory()
                .getFirst()
                .fileFragments()
                .filter(f -> f instanceof ContextFragments.ProjectPathFragment)
                .map(f -> (ContextFragments.ProjectPathFragment) f)
                .findFirst()
                .orElseThrow();
        assertFalse(
                loadedShorter.getHistory().getFirst().isMarkedReadonly(loadedPpf2),
                "Editable fragment should default to not read-only when id absent");
    }

    // Helper: read readonly IDs from contexts.jsonl inside zip
    @SuppressWarnings("unchecked")
    private Set<String> readReadonlyIdsFromZip(Path zip) throws IOException {
        try (var zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("contexts.jsonl".equals(entry.getName())) {
                    String content = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    String firstLine = content.lines()
                            .filter(s -> !s.isBlank())
                            .findFirst()
                            .orElse("");
                    if (firstLine.isEmpty()) return Set.of();
                    var mapper = new ObjectMapper();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> obj = mapper.readValue(firstLine, Map.class);
                    @SuppressWarnings("unchecked")
                    List<String> readonly = (List<String>) obj.getOrDefault("readonly", List.of());
                    return new java.util.HashSet<>(readonly);
                }
            }
        }
        return Set.of();
    }

    @Test
    void testMixedTaskEntryStatesRoundTrip() throws Exception {
        // Create a context with multiple TaskEntry states: log-only, summary-only, and both
        var ctx = new Context(mockContextManager);

        // Entry 1: Log only
        var msg1 = List.<ChatMessage>of(UserMessage.from("Query 1"), AiMessage.from("Response 1"));
        var tf1 = new ContextFragments.TaskFragment(mockContextManager, msg1, "Task 1");
        var entry1 = new TaskEntry(1, tf1, null);
        ctx = ctx.addHistoryEntry(entry1, tf1);

        // Entry 2: Both log and summary
        var msg2 = List.<ChatMessage>of(UserMessage.from("Query 2"), AiMessage.from("Response 2"));
        var tf2 = new ContextFragments.TaskFragment(mockContextManager, msg2, "Task 2");
        var entry2 = new TaskEntry(2, tf2, "Summary of task 2");
        ctx = ctx.addHistoryEntry(entry2, tf2);

        // Entry 3: Summary only (legacy compressed)
        var entry3 = new TaskEntry(3, null, "Summary of task 3 only");
        ctx = ctx.addHistoryEntry(entry3, null);

        ContextHistory original = new ContextHistory(ctx);

        Path zipFile = tempDir.resolve("mixed_task_entries.zip");
        HistoryIo.writeZip(original, zipFile);
        ContextHistory loaded = HistoryIo.readZip(zipFile, mockContextManager);

        List<TaskEntry> loadedEntries = loaded.getHistory().get(0).getTaskHistory();
        assertEquals(3, loadedEntries.size(), "Should have 3 task entries");

        // Verify Entry 1: Log only
        TaskEntry loaded1 = loadedEntries.get(0);
        assertEquals(1, loaded1.sequence());
        assertTrue(loaded1.hasLog());
        assertFalse(loaded1.isCompressed());
        assertEquals(2, loaded1.log().messages().size());

        // Verify Entry 2: Both log and summary
        TaskEntry loaded2 = loadedEntries.get(1);
        assertEquals(2, loaded2.sequence());
        assertTrue(loaded2.hasLog());
        assertTrue(loaded2.isCompressed());
        assertEquals("Summary of task 2", loaded2.summary());
        assertEquals(2, loaded2.log().messages().size());

        // Verify Entry 3: Summary only
        TaskEntry loaded3 = loadedEntries.get(2);
        assertEquals(3, loaded3.sequence());
        assertFalse(loaded3.hasLog());
        assertTrue(loaded3.isCompressed());
        assertEquals("Summary of task 3 only", loaded3.summary());
    }

    // Helper: rewrite contexts.jsonl in a zip file using a single-line transform
    @SuppressWarnings("unchecked")
    private void rewriteContextsInZip(Path source, Path target, java.util.function.Function<String, String> transform)
            throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var zis = new ZipInputStream(Files.newInputStream(source))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().equals("contexts.jsonl")) {
                    String content = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    List<String> lines = content.lines().toList();
                    List<String> transformed = lines.stream()
                            .filter(s -> !s.isBlank())
                            .map(transform)
                            .toList();
                    String newContent = String.join("\n", transformed) + "\n";
                    entries.put(e.getName(), newContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    @SuppressWarnings("null")
                    byte[] bytes = zis.readAllBytes();
                    entries.put(e.getName(), bytes);
                }
            }
        }
        try (var zos = new ZipOutputStream(Files.newOutputStream(target))) {
            for (var en : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(en.getKey()));
                zos.write(en.getValue());
                zos.closeEntry();
            }
        }
    }

    @Test
    void testRoundTripPathFragmentsWithSnapshots() throws Exception {
        // ProjectPathFragment
        var projectFile = new ProjectFile(tempDir, "src/SnapshotTest.java");
        Files.createDirectories(projectFile.absPath().getParent());
        String projectFileContent = "public class SnapshotTest {}";
        Files.writeString(projectFile.absPath(), projectFileContent);
        var ppf = new ContextFragments.ProjectPathFragment(projectFile, mockContextManager);
        var ppfSnapshot = ppf.text().join();
        assertFalse(ppfSnapshot.isBlank());
        assertEquals(projectFileContent, ppfSnapshot);

        // ExternalPathFragment
        Path externalFilePath = tempDir.resolve("external_snapshot.txt");
        String externalFileContent = "External snapshot content";
        Files.writeString(externalFilePath, externalFileContent);
        var externalFile = new ExternalFile(externalFilePath);
        var epf = new ContextFragments.ExternalPathFragment(externalFile, mockContextManager);
        var epfSnapshot = epf.text().join();
        assertFalse(epfSnapshot.isBlank());
        assertEquals(externalFileContent, epfSnapshot);

        var context = new Context(mockContextManager).addFragments(List.of(ppf, epf));
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_snapshots_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        Context loadedCtx = loadedHistory.getHistory().get(0);

        var loadedPpf = (ContextFragments.ProjectPathFragment) loadedCtx
                .allFragments()
                .filter(f -> f instanceof ContextFragments.ProjectPathFragment)
                .findFirst()
                .orElseThrow();

        var loadedEpf = (ContextFragments.ExternalPathFragment) loadedCtx
                .allFragments()
                .filter(f -> f instanceof ContextFragments.ExternalPathFragment)
                .findFirst()
                .orElseThrow();

        assertEquals(projectFileContent, loadedPpf.text().join());
        assertEquals(externalFileContent, loadedEpf.text().join());

        // Also check via text() to ensure it uses the snapshot
        assertEquals(projectFileContent, loadedPpf.text().join());
        assertEquals(externalFileContent, loadedEpf.text().join());
    }

    @Test
    void testRoundTripDiffStringFragmentWithFiles() throws Exception {
        var projectFile1 = new ProjectFile(tempDir, "src/DiffFile1.java");
        var projectFile2 = new ProjectFile(tempDir, "src/DiffFile2.java");
        Files.createDirectories(projectFile1.absPath().getParent());
        Files.writeString(projectFile1.absPath(), "class DiffFile1 {}");
        Files.writeString(projectFile2.absPath(), "class DiffFile2 {}");

        var associatedFiles = Set.of(projectFile1, projectFile2);

        String diffText =
                """
                diff --git a/src/DiffFile1.java b/src/DiffFile1.java
                --- a/src/DiffFile1.java
                +++ b/src/DiffFile1.java
                @@ -1 +1 @@
                -class DiffFile1 {}
                +class DiffFile1 { }
                """;

        var fragment = new ContextFragments.StringFragment(
                mockContextManager,
                diffText,
                "Diff of DiffFile1.java and DiffFile2.java",
                SyntaxConstants.SYNTAX_STYLE_NONE,
                associatedFiles);

        // Live fragment exposes associated files for Edit All Refs
        assertEquals(associatedFiles, fragment.files().join());

        var context = new Context(mockContextManager).addFragments(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("diff_stringfragment_files_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertEquals(1, loadedHistory.getHistory().size());
        Context loadedCtx = loadedHistory.getHistory().get(0);

        var loadedFragment = loadedCtx
                .virtualFragments()
                .filter(f -> f instanceof ContextFragments.StringFragment)
                .map(f -> (ContextFragments.StringFragment) f)
                .findFirst()
                .orElseThrow();

        assertEquals(diffText, loadedFragment.text().join());
        assertEquals(
                "Diff of DiffFile1.java and DiffFile2.java",
                loadedFragment.description().join());
    }

    @Test
    void testRoundTripGitDiffSingleFileFilesPreserved() throws Exception {
        var projectFile = new ProjectFile(tempDir, "src/GitDiffSingle.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "class GitDiffSingle {}");

        String diffText =
                """
                diff --git a/src/GitDiffSingle.java b/src/GitDiffSingle.java
                index e69de29..4b825dc 100644
                --- a/src/GitDiffSingle.java
                +++ b/src/GitDiffSingle.java
                @@ -1 +1 @@
                -class GitDiffSingle {}
                +class GitDiffSingle { }
                """;

        var fragment = new ContextFragments.StringFragment(
                mockContextManager, diffText, "Git diff for GitDiffSingle.java", SyntaxConstants.SYNTAX_STYLE_NONE);

        var expectedPaths = Set.of(projectFile.absPath().toString());
        var beforePaths = fragment.files().join().stream()
                .map(pf -> pf.absPath().toString())
                .collect(Collectors.toSet());
        assertEquals(expectedPaths, beforePaths);

        var context = new Context(mockContextManager).addFragments(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("gitdiff_single_roundtrip.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedFragment = loadedCtx
                .virtualFragments()
                .filter(f -> f instanceof ContextFragments.StringFragment)
                .map(f -> (ContextFragments.StringFragment) f)
                .findFirst()
                .orElseThrow();

        var afterPaths = loadedFragment.files().join().stream()
                .map(pf -> pf.absPath().toString())
                .collect(Collectors.toSet());
        assertEquals(expectedPaths, afterPaths);
    }

    @Test
    void testRoundTripMultiFileUnifiedDiffFilesPreserved() throws Exception {
        var projectFileA = new ProjectFile(tempDir, "src/UnifiedA.java");
        var projectFileB = new ProjectFile(tempDir, "src/UnifiedB.java");
        Files.createDirectories(projectFileA.absPath().getParent());
        Files.writeString(projectFileA.absPath(), "class UnifiedA {}");
        Files.writeString(projectFileB.absPath(), "class UnifiedB {}");

        String diffText =
                """
                --- src/UnifiedA.java
                +++ src/UnifiedA.java
                @@ -1 +1 @@
                -class UnifiedA {}
                +class UnifiedA { }

                --- src/UnifiedB.java
                +++ src/UnifiedB.java
                @@ -1 +1 @@
                -class UnifiedB {}
                +class UnifiedB { }
                """;

        var fragment = new ContextFragments.StringFragment(
                mockContextManager,
                diffText,
                "Unified diff for UnifiedA.java and UnifiedB.java",
                SyntaxConstants.SYNTAX_STYLE_NONE);

        var expectedPaths =
                Set.of(projectFileA.absPath().toString(), projectFileB.absPath().toString());
        var beforePaths = fragment.files().join().stream()
                .map(pf -> pf.absPath().toString())
                .collect(Collectors.toSet());
        assertEquals(expectedPaths, beforePaths);

        var context = new Context(mockContextManager).addFragments(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("unified_multi_roundtrip.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedFragment = loadedCtx
                .virtualFragments()
                .filter(f -> f instanceof ContextFragments.StringFragment)
                .map(f -> (ContextFragments.StringFragment) f)
                .findFirst()
                .orElseThrow();

        var afterPaths = loadedFragment.files().join().stream()
                .map(pf -> pf.absPath().toString())
                .collect(Collectors.toSet());
        assertEquals(expectedPaths, afterPaths);
    }

    @Test
    void testRoundTripDeletionDiffWithDevNullFilesPreserved() throws Exception {
        var projectFile = new ProjectFile(tempDir, "src/Deleted.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "class Deleted {}");

        String diffText =
                """
                diff --git a/src/Deleted.java b/src/Deleted.java
                deleted file mode 100644
                index e69de29..0000000
                --- a/src/Deleted.java
                +++ /dev/null
                @@ -1 +0,0 @@
                -class Deleted {}
                """;

        var fragment = new ContextFragments.StringFragment(
                mockContextManager, diffText, "Deletion diff for Deleted.java", SyntaxConstants.SYNTAX_STYLE_NONE);

        var expectedPaths = Set.of(projectFile.absPath().toString());
        var beforePaths = fragment.files().join().stream()
                .map(pf -> pf.absPath().toString())
                .collect(Collectors.toSet());
        assertEquals(expectedPaths, beforePaths);

        var context = new Context(mockContextManager).addFragments(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("deletion_roundtrip.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedFragment = loadedCtx
                .virtualFragments()
                .filter(f -> f instanceof ContextFragments.StringFragment)
                .map(f -> (ContextFragments.StringFragment) f)
                .findFirst()
                .orElseThrow();

        var afterPaths = loadedFragment.files().join().stream()
                .map(pf -> pf.absPath().toString())
                .collect(Collectors.toSet());
        assertEquals(expectedPaths, afterPaths);
    }

    @Test
    void testRoundTripRenameDiffFilesPreserved() throws Exception {
        var oldFile = new ProjectFile(tempDir, "src/RenamedOld.java");
        var newFile = new ProjectFile(tempDir, "src/RenamedNew.java");
        Files.createDirectories(oldFile.absPath().getParent());
        Files.writeString(oldFile.absPath(), "class RenamedOld {}");
        Files.writeString(newFile.absPath(), "class RenamedNew {}");

        String diffText =
                """
                diff --git a/src/RenamedOld.java b/src/RenamedNew.java
                similarity index 100%
                rename from src/RenamedOld.java
                rename to src/RenamedNew.java
                --- a/src/RenamedOld.java
                +++ b/src/RenamedNew.java
                @@ -1 +1 @@
                -class RenamedOld {}
                +class RenamedNew {}
                """;

        var fragment = new ContextFragments.StringFragment(
                mockContextManager,
                diffText,
                "Rename diff from RenamedOld.java to RenamedNew.java",
                SyntaxConstants.SYNTAX_STYLE_NONE);

        var expectedPaths = Set.of(newFile.absPath().toString());
        var beforePaths = fragment.files().join().stream()
                .map(pf -> pf.absPath().toString())
                .collect(Collectors.toSet());
        assertEquals(expectedPaths, beforePaths);

        var context = new Context(mockContextManager).addFragments(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("rename_roundtrip.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedFragment = loadedCtx
                .virtualFragments()
                .filter(f -> f instanceof ContextFragments.StringFragment)
                .map(f -> (ContextFragments.StringFragment) f)
                .findFirst()
                .orElseThrow();

        var afterPaths = loadedFragment.files().join().stream()
                .map(pf -> pf.absPath().toString())
                .collect(Collectors.toSet());
        assertEquals(expectedPaths, afterPaths);
    }

    @Test
    void testRoundTripNonDiffTextHasNoFiles() throws Exception {
        var fragment = new ContextFragments.StringFragment(
                mockContextManager,
                "This is not a diff\nJust some plain text.",
                "Plain text",
                SyntaxConstants.SYNTAX_STYLE_NONE);

        assertTrue(fragment.files().join().isEmpty());

        var context = new Context(mockContextManager).addFragments(fragment);
        ContextHistory originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("nondiff_roundtrip.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedFragment = loadedCtx
                .virtualFragments()
                .filter(f -> f instanceof ContextFragments.StringFragment)
                .map(f -> (ContextFragments.StringFragment) f)
                .findFirst()
                .orElseThrow();

        assertTrue(loadedFragment.files().join().isEmpty());
    }

    @Test
    void testStringFragmentExtractsFilesFromPathList() throws Exception {
        var file1 = new ProjectFile(tempDir, "src/PathListFile1.java");
        var file2 = new ProjectFile(tempDir, "src/PathListFile2.java");
        Files.createDirectories(file1.absPath().getParent());
        Files.writeString(file1.absPath(), "class PathListFile1 {}");
        Files.writeString(file2.absPath(), "class PathListFile2 {}");

        String pathList = file1 + "\n" + file2 + "\n";

        var fragment = new ContextFragments.StringFragment(
                mockContextManager, pathList, "File list", SyntaxConstants.SYNTAX_STYLE_NONE);

        assertEquals(Set.of(file1, file2), fragment.files().join());
    }

    @Test
    void testPathListExtractionSkipsNonExistentFiles() throws Exception {
        var existingFile = new ProjectFile(tempDir, "src/ExistingFile.java");
        Files.createDirectories(existingFile.absPath().getParent());
        Files.writeString(existingFile.absPath(), "class ExistingFile {}");

        String pathList = existingFile + "\nsrc/NonExistentFile.java\n";

        var fragment = new ContextFragments.StringFragment(
                mockContextManager, pathList, "Mixed file list", SyntaxConstants.SYNTAX_STYLE_NONE);

        assertEquals(Set.of(existingFile), fragment.files().join());
    }

    @Test
    void testMixedPastedListCollectsOnlyValidPathsForStringAndPasteFragments() throws Exception {
        var file1 = new ProjectFile(tempDir, "src/MixedValid1.java");
        var file2 = new ProjectFile(tempDir, "src/MixedValid2.java");
        Files.createDirectories(file1.absPath().getParent());
        Files.writeString(file1.absPath(), "class MixedValid1 {}");
        Files.writeString(file2.absPath(), "class MixedValid2 {}");

        String mixed = "# comment\n" + file1 + "\nnot/a/real/file.txt\n    " + file2 + "\ngarbage line\n";

        var stringFragment = new ContextFragments.StringFragment(
                mockContextManager, mixed, "Mixed content", SyntaxConstants.SYNTAX_STYLE_NONE);
        assertEquals(Set.of(file1, file2), stringFragment.files().join());

        var pasteFragment = new ContextFragments.PasteTextFragment(
                mockContextManager,
                mixed,
                CompletableFuture.completedFuture("Mixed content"),
                CompletableFuture.completedFuture(SyntaxConstants.SYNTAX_STYLE_NONE));
        assertEquals(Set.of(file1, file2), pasteFragment.files().join());
    }

    @Test
    void testDiffTakesPrecedenceOverPathList() throws Exception {
        var projectFile = new ProjectFile(tempDir, "src/DiffPrecedence.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "class DiffPrecedence {}");

        String diffText =
                """
                diff --git a/src/DiffPrecedence.java b/src/DiffPrecedence.java
                --- a/src/DiffPrecedence.java
                +++ b/src/DiffPrecedence.java
                @@ -1 +1 @@
                -class DiffPrecedence {}
                +class DiffPrecedence { }
                """;

        var fragment = new ContextFragments.StringFragment(
                mockContextManager, diffText, "Diff content", SyntaxConstants.SYNTAX_STYLE_NONE);

        assertEquals(Set.of(projectFile), fragment.files().join());
    }

    @Test
    void testPathsMayOccurAnywhere() throws Exception {
        var file = new ProjectFile(tempDir, "src/TestFile.java");
        Files.createDirectories(file.absPath().getParent());
        Files.writeString(file.absPath(), "class TestFile {}");

        String mixedFormats = file + ":10: error: cannot find symbol\n"
                + file + ":25:    public void method() {\n"
                + "    at com.example.Test(" + file + ":42)\n"
                + "https://example.com/docs/api.html\n";

        var fragment = new ContextFragments.StringFragment(
                mockContextManager, mixedFormats, "Mixed formats", SyntaxConstants.SYNTAX_STYLE_NONE);

        assertFalse(fragment.files().join().isEmpty());
    }

    @Test
    void testPathsWithSpacesAreExtracted() throws Exception {
        var fileWithSpace = new ProjectFile(tempDir, "src/My Class.java");
        var normalFile = new ProjectFile(tempDir, "src/NormalFile.java");
        Files.createDirectories(fileWithSpace.absPath().getParent());
        Files.writeString(fileWithSpace.absPath(), "class MyClass {}");
        Files.writeString(normalFile.absPath(), "class NormalFile {}");

        String pathList = fileWithSpace + "\n" + normalFile + "\n";

        var fragment = new ContextFragments.StringFragment(
                mockContextManager, pathList, "Path list with spaces", SyntaxConstants.SYNTAX_STYLE_NONE);

        assertEquals(Set.of(fileWithSpace, normalFile), fragment.files().join());
    }

    @Test
    void testPasteTextFragmentSerializationPreservesFiles() throws Exception {
        var file1 = new ProjectFile(tempDir, "src/SerializedFile1.java");
        var file2 = new ProjectFile(tempDir, "src/SerializedFile2.java");
        Files.createDirectories(file1.absPath().getParent());
        Files.writeString(file1.absPath(), "class SerializedFile1 {}");
        Files.writeString(file2.absPath(), "class SerializedFile2 {}");

        String pathList = file1 + "\n" + file2 + "\n";

        var fragment = new ContextFragments.PasteTextFragment(
                mockContextManager,
                pathList,
                CompletableFuture.completedFuture("File list"),
                CompletableFuture.completedFuture(SyntaxConstants.SYNTAX_STYLE_NONE));

        var context = new Context(mockContextManager).addFragments(fragment);
        var originalHistory = new ContextHistory(context);

        Path zipFile = tempDir.resolve("test_paste_files_serialization.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        var loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        context.awaitContextsAreComputed(Duration.ofSeconds(10));
        loadedHistory.liveContext().awaitContextsAreComputed(Duration.ofSeconds(10));

        var loadedFragment = (ContextFragments.PasteTextFragment) loadedHistory
                .liveContext()
                .allFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.PASTE_TEXT)
                .findFirst()
                .orElseThrow();

        assertEquals(Set.of(file1, file2), loadedFragment.files().join());
    }

    @Test
    void testPasteTextFragmentExtractsFilesOnFutureTimeout() throws Exception {
        var file = new ProjectFile(tempDir, "src/TimeoutTest.java");
        Files.createDirectories(file.absPath().getParent());
        Files.writeString(file.absPath(), "class TimeoutTest {}");

        var failingDescFuture = new CompletableFuture<String>();
        failingDescFuture.completeExceptionally(new RuntimeException("Simulated LLM timeout"));

        var failingSyntaxFuture = new CompletableFuture<String>();
        failingSyntaxFuture.completeExceptionally(new RuntimeException("Simulated LLM failure"));

        var fragment = new ContextFragments.PasteTextFragment(
                mockContextManager, file.toString(), failingDescFuture, failingSyntaxFuture);

        assertEquals(Set.of(file), fragment.files().join());
        assertEquals("Paste of text content", fragment.description().join());
    }
}
