package io.github.jbellis.brokk.context;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.Project;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.testutil.TestContextManager;
import io.github.jbellis.brokk.util.HistoryIo;
import io.github.jbellis.brokk.util.Messages;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class ContextSerializationTest {
    @TempDir
    Path tempDir;
    private IContextManager mockContextManager;

    @BeforeEach
    void setup() throws IOException {
        mockContextManager = new TestContextManager(tempDir);
        // Clear the intern pool before each test to ensure isolation
        FrozenFragment.clearInternPoolForTesting();
        // Reset fragment ID counter for test isolation
        ContextFragment.nextId.set(1);
        
        // Clean .brokk/sessions directory for session tests
        Path sessionsDir = tempDir.resolve(".brokk").resolve("sessions");
        if (Files.exists(sessionsDir)) {
            try (var stream = Files.walk(sessionsDir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                     .forEach(path -> {
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

    private byte[] imageToBytes(Image image) throws IOException {
        if (image == null) return null;
        BufferedImage bufferedImage = (image instanceof BufferedImage) ? (BufferedImage) image :
                                      new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        if (!(image instanceof BufferedImage)) {
            Graphics2D bGr = bufferedImage.createGraphics();
            bGr.drawImage(image, 0, 0, null);
            bGr.dispose();
        }
        try (var baos = new ByteArrayOutputStream()) {
            ImageIO.write(bufferedImage, "PNG", baos);
            return baos.toByteArray();
        }
    }

    // --- Tests for HistoryIo ---

    @Test
    void testWriteReadEmptyHistory() throws IOException {
        var history = new ContextHistory();
        Path zipFile = tempDir.resolve("empty_history.zip");

        HistoryIo.writeZip(history, zipFile);
        assertTrue(Files.exists(zipFile));

        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);
        assertNotNull(loadedHistory);
        assertTrue(loadedHistory.getHistory().isEmpty());
    }

    @Test
    void testReadNonExistentZip() throws IOException {
        Path zipFile = tempDir.resolve("non_existent.zip");
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);
        assertNotNull(loadedHistory);
        assertTrue(loadedHistory.getHistory().isEmpty(), "Reading a non-existent zip should result in an empty history.");
    }

    @Test
    void testWriteReadHistoryWithSingleContext_NoFragments() throws IOException {
        var history = new ContextHistory();
        var initialContext = new Context(mockContextManager, "Initial welcome.");
        history.setInitialContext(initialContext.freezeForTesting()); // Freeze context

        Path zipFile = tempDir.resolve("single_context_no_fragments.zip");
        HistoryIo.writeZip(history, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertEquals(1, loadedHistory.getHistory().size());
        // Further assertions can be added to compare context details if necessary,
        // focusing on serializable aspects.
        // For a "no fragments" context, primarily the task history (welcome message) is relevant.
        Context loadedCtx = loadedHistory.getHistory().get(0);
        assertNotNull(loadedCtx.getParsedOutput()); // Welcome message
        assertEquals("Welcome", loadedCtx.getParsedOutput().description());
    }


    @Test
    void testWriteReadHistoryWithComplexContent() throws Exception {
        ContextHistory originalHistory = new ContextHistory();

        // Context 1: Project file, string fragment
        var projectFile1 = new ProjectFile(tempDir, "src/File1.java");
        Files.createDirectories(projectFile1.absPath().getParent());
        Files.writeString(projectFile1.absPath(), "public class File1 {}");
        var context1 = new Context(mockContextManager, "Context 1 started")
                .addEditableFiles(List.of(new ContextFragment.ProjectPathFragment(projectFile1, mockContextManager)))
                .addVirtualFragment(new ContextFragment.StringFragment(mockContextManager, "Virtual content 1", "VC1", SyntaxConstants.SYNTAX_STYLE_JAVA));
        originalHistory.setInitialContext(context1.freezeForTesting()); // Freeze context

        // Context 2: Image fragment, task history
        var image1 = createTestImage(Color.RED, 10, 10);
        var pasteImageFragment1 = new ContextFragment.PasteImageFragment(mockContextManager, image1, CompletableFuture.completedFuture("Pasted Red Image"));
        
        var context2 = new Context(mockContextManager, "Context 2 started")
                .addVirtualFragment(pasteImageFragment1);
        
        List<ChatMessage> taskMessages = List.of(UserMessage.from("User query"), AiMessage.from("AI response"));
        var taskFragment = new ContextFragment.TaskFragment(mockContextManager, taskMessages, "Test Task");
        context2 = context2.addHistoryEntry(new TaskEntry(1, taskFragment, null), taskFragment, CompletableFuture.completedFuture("Action for task"));
        
        originalHistory.addFrozenContextAndClearRedo(context2.freezeForTesting());
        
        Path zipFile = tempDir.resolve("complex_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        // Assertions
        assertEquals(originalHistory.getHistory().size(), loadedHistory.getHistory().size());

        // Compare Context 1 (which will be frozen)
        Context originalCtx1Frozen = originalHistory.getHistory().get(0); // This is already frozen by ContextHistory
        Context loadedCtx1 = loadedHistory.getHistory().get(0);
        assertContextsEqual(originalCtx1Frozen, loadedCtx1);


        // Compare Context 2 (which will be frozen)
        Context originalCtx2Frozen = originalHistory.getHistory().get(1); // This is already frozen by ContextHistory
        Context loadedCtx2 = loadedHistory.getHistory().get(1);
        assertContextsEqual(originalCtx2Frozen, loadedCtx2);

        // Verify image content from the image fragment in loadedCtx2
        var loadedImageFragmentOpt = loadedCtx2.virtualFragments()
            .filter(f -> !f.isText() && "Paste of Pasted Red Image".equals(f.description()))
            .findFirst();
        assertTrue(loadedImageFragmentOpt.isPresent(), "Pasted Red Image fragment not found in loaded context 2");
        var loadedImageFragment = loadedImageFragmentOpt.get();

        byte[] imageBytesContent;
        if (loadedImageFragment instanceof FrozenFragment ff) {
            imageBytesContent = ff.imageBytesContent();
        } else if (loadedImageFragment instanceof ContextFragment.PasteImageFragment pif) {
            imageBytesContent = imageToBytes(pif.image());
        } else {
            throw new AssertionError("Unexpected fragment type for pasted image: " + loadedImageFragment.getClass());
        }
        
        assertNotNull(imageBytesContent);
        assertTrue(imageBytesContent.length > 0);
        Image loadedImage = ImageIO.read(new java.io.ByteArrayInputStream(imageBytesContent));
        assertNotNull(loadedImage);
        assertEquals(10, loadedImage.getWidth(null));
        assertEquals(10, loadedImage.getHeight(null));
        // Could do a pixel-by-pixel comparison if necessary
    }

    private void assertContextsEqual(Context expected, Context actual) throws IOException, InterruptedException {
        // Compare editable files
        var expectedEditable = expected.editableFiles().sorted(java.util.Comparator.comparingInt(ContextFragment::id)).toList();
        var actualEditable = actual.editableFiles().sorted(java.util.Comparator.comparingInt(ContextFragment::id)).toList();
        assertEquals(expectedEditable.size(), actualEditable.size(), "Editable files count mismatch");
        for (int i = 0; i < expectedEditable.size(); i++) {
            assertContextFragmentsEqual(expectedEditable.get(i), actualEditable.get(i));
        }

        // Compare readonly files
        var expectedReadonly = expected.readonlyFiles().sorted(java.util.Comparator.comparingInt(ContextFragment::id)).toList();
        var actualReadonly = actual.readonlyFiles().sorted(java.util.Comparator.comparingInt(ContextFragment::id)).toList();
        assertEquals(expectedReadonly.size(), actualReadonly.size(), "Readonly files count mismatch");
        for (int i = 0; i < expectedReadonly.size(); i++) {
            assertContextFragmentsEqual(expectedReadonly.get(i), actualReadonly.get(i));
        }
        
        // Compare virtual fragments
        var expectedVirtuals = expected.virtualFragments().sorted(java.util.Comparator.comparingInt(ContextFragment::id)).toList();
        var actualVirtuals = actual.virtualFragments().sorted(java.util.Comparator.comparingInt(ContextFragment::id)).toList();
        assertEquals(expectedVirtuals.size(), actualVirtuals.size(), "Virtual fragments count mismatch");
        for (int i = 0; i < expectedVirtuals.size(); i++) {
            assertContextFragmentsEqual(expectedVirtuals.get(i), actualVirtuals.get(i));
        }
        
        // Compare task history
        assertEquals(expected.getTaskHistory().size(), actual.getTaskHistory().size(), "Task history size mismatch");
        for (int i = 0; i < expected.getTaskHistory().size(); i++) {
            assertTaskEntriesEqual(expected.getTaskHistory().get(i), actual.getTaskHistory().get(i));
        }
    }

    private void assertContextFragmentsEqual(ContextFragment expected, ContextFragment actual) throws IOException, InterruptedException {
        assertEquals(expected.id(), actual.id(), "Fragment ID mismatch");
        assertEquals(expected.getType(), actual.getType(), "Fragment type mismatch for ID " + expected.id());
        assertEquals(expected.description(), actual.description(), "Fragment description mismatch for ID " + expected.id());
        assertEquals(expected.shortDescription(), actual.shortDescription(), "Fragment shortDescription mismatch for ID " + expected.id());
        assertEquals(expected.isText(), actual.isText(), "Fragment isText mismatch for ID " + expected.id());
        assertEquals(expected.syntaxStyle(), actual.syntaxStyle(), "Fragment syntaxStyle mismatch for ID " + expected.id());

        if (expected.isText()) {
            assertEquals(expected.text(), actual.text(), "Fragment text content mismatch for ID " + expected.id());
        } else {
            // For image fragments, compare byte content if both are FrozenFragment or can provide bytes
            if (expected instanceof FrozenFragment expectedFf && actual instanceof FrozenFragment actualFf) {
                assertArrayEquals(expectedFf.imageBytesContent(), actualFf.imageBytesContent(), "FrozenFragment imageBytesContent mismatch for ID " + expected.id());
            } else if (expected.image() != null && actual.image() != null) { // Fallback for non-frozen, if any after freezing
                assertArrayEquals(imageToBytes(expected.image()), imageToBytes(actual.image()), "Fragment image content mismatch for ID " + expected.id());
            }
        }

        // Compare files (ProjectFile and CodeUnit DTOs are by value)
        // FrozenFragment.sources() intentionally throws UnsupportedOperationException, so untested
        if (!(expected instanceof FrozenFragment)) {
            assertEquals(expected.sources().stream().map(CodeUnit::fqName).collect(Collectors.toSet()),
                         actual.sources().stream().map(CodeUnit::fqName).collect(Collectors.toSet()),
                         "Fragment sources mismatch for ID " + expected.id());
        }
        assertEquals(expected.files().stream().map(ProjectFile::toString).collect(Collectors.toSet()),
                     actual.files().stream().map(ProjectFile::toString).collect(Collectors.toSet()),
                     "Fragment files mismatch for ID " + expected.id());

        if (expected instanceof FrozenFragment expectedFf && actual instanceof FrozenFragment actualFf) {
            assertEquals(expectedFf.originalClassName(), actualFf.originalClassName(), "FrozenFragment originalClassName mismatch for ID " + expected.id());
            assertEquals(expectedFf.meta(), actualFf.meta(), "FrozenFragment meta mismatch for ID " + expected.id());
        }
    }
    
    private void assertTaskEntriesEqual(TaskEntry expected, TaskEntry actual) {
        assertEquals(expected.sequence(), actual.sequence());
        assertEquals(expected.isCompressed(), actual.isCompressed());
        if (expected.isCompressed()) {
            assertEquals(expected.summary(), actual.summary());
        } else {
            assertNotNull(expected.log());
            assertNotNull(actual.log());
            assertEquals(expected.log().description(), actual.log().description());
            assertEquals(expected.log().messages().size(), actual.log().messages().size());
            for (int i = 0; i < expected.log().messages().size(); i++) {
                ChatMessage expectedMsg = expected.log().messages().get(i);
                ChatMessage actualMsg = actual.log().messages().get(i);
                assertEquals(expectedMsg.type(), actualMsg.type());
                assertEquals(Messages.getRepr(expectedMsg), Messages.getRepr(actualMsg));
            }
        }
    }

    @Test
    void testWriteReadHistoryWithSharedImageFragment() throws Exception {
        // Create a shared image
        var sharedImage = createTestImage(Color.BLUE, 8, 8);
        
        // Create two PasteImageFragments with identical content and description
        // This should result in the same FrozenFragment instance due to interning
        var sharedDescription = "Shared Blue Image";
        var liveImageFrag1 = new ContextFragment.PasteImageFragment(
            mockContextManager, 
            sharedImage, 
            CompletableFuture.completedFuture(sharedDescription)
        );
        var liveImageFrag2 = new ContextFragment.PasteImageFragment(
            mockContextManager, 
            sharedImage, 
            CompletableFuture.completedFuture(sharedDescription)
        );
        
        // Create history with two contexts containing the shared image fragments
        var originalHistory = new ContextHistory();
        
        // Context 1 with first image fragment
        var ctx1 = new Context(mockContextManager, "Context 1 with shared image")
            .addVirtualFragment(liveImageFrag1);
        originalHistory.setInitialContext(ctx1.freezeForTesting()); // Freeze context
        
        // Context 2 with second image fragment (same content, should intern to same FrozenFragment)
        var ctx2 = new Context(mockContextManager, "Context 2 with shared image")
            .addVirtualFragment(liveImageFrag2);
        originalHistory.addFrozenContextAndClearRedo(ctx2.freezeForTesting());
        
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
        var fragment1 = loadedCtx1.virtualFragments()
            .filter(f -> !f.isText() && "Paste of Shared Blue Image".equals(f.description()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Image fragment not found in loaded context 1"));
            
        var fragment2 = loadedCtx2.virtualFragments()
            .filter(f -> !f.isText() && "Paste of Shared Blue Image".equals(f.description()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Image fragment not found in loaded context 2"));
        
        byte[] imageBytes1, imageBytes2;
        if (fragment1 instanceof FrozenFragment ff1) {
            imageBytes1 = ff1.imageBytesContent();
        } else if (fragment1 instanceof ContextFragment.PasteImageFragment pif1) {
            imageBytes1 = imageToBytes(pif1.image());
        } else {
            throw new AssertionError("Unexpected fragment type for image in ctx1: " + fragment1.getClass());
        }

        if (fragment2 instanceof FrozenFragment ff2) {
            imageBytes2 = ff2.imageBytesContent();
        } else if (fragment2 instanceof ContextFragment.PasteImageFragment pif2) {
            imageBytes2 = imageToBytes(pif2.image());
        } else {
            throw new AssertionError("Unexpected fragment type for image in ctx2: " + fragment2.getClass());
        }

        // Verify image content
        assertNotNull(imageBytes1);
        assertNotNull(imageBytes2);
        assertTrue(imageBytes1.length > 0);
        assertTrue(imageBytes2.length > 0);
        
        // Verify the image can be read back
        var reconstructedImage1 = ImageIO.read(new java.io.ByteArrayInputStream(imageBytes1));
        var reconstructedImage2 = ImageIO.read(new java.io.ByteArrayInputStream(imageBytes2));
        assertNotNull(reconstructedImage1);
        assertNotNull(reconstructedImage2);
        assertEquals(8, reconstructedImage1.getWidth());
        assertEquals(8, reconstructedImage1.getHeight());
        assertEquals(8, reconstructedImage2.getWidth());
        assertEquals(8, reconstructedImage2.getHeight());
        
        // Verify descriptions
        assertEquals("Paste of " + sharedDescription, fragment1.description());
        assertEquals("Paste of " + sharedDescription, fragment2.description());
    }

    @Test
    void testFragmentIdContinuityAfterLoad() throws IOException {
        var history = new ContextHistory();
        var projectFile = new ProjectFile(tempDir, "dummy.txt");
        Files.writeString(projectFile.absPath(), "content");
        var ctxFragment = new ContextFragment.ProjectPathFragment(projectFile, mockContextManager); // ID 1
        var strFragment = new ContextFragment.StringFragment(mockContextManager, "text", "desc", SyntaxConstants.SYNTAX_STYLE_NONE); // ID 2
        
        var context = new Context(mockContextManager, "Initial")
            .addEditableFiles(List.of(ctxFragment))
            .addVirtualFragment(strFragment);
        history.setInitialContext(context.freezeForTesting()); // Freeze context

        Path zipFile = tempDir.resolve("id_continuity_history.zip");
        HistoryIo.writeZip(history, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager); // Deserialization updates ContextFragment.nextId

        int maxIdFromLoadedFragments = loadedHistory.getHistory().stream()
            .flatMap(Context::allFragments)
            .mapToInt(ContextFragment::id)
            .max().orElse(0);

        // CurrentNextId should be maxId + 1
        int currentNextId = ContextFragment.getCurrentMaxId();
        assertTrue(currentNextId > maxIdFromLoadedFragments, 
                   "ContextFragment.nextId should be greater than the max ID found in loaded fragments.");
        
        // Create a new fragment; it should get `currentNextId`
        var newFragment = new ContextFragment.StringFragment(mockContextManager, "new", "new desc", SyntaxConstants.SYNTAX_STYLE_NONE);
        assertEquals(currentNextId, newFragment.id(), "New fragment should get the expected next ID.");
        assertEquals(currentNextId + 1, ContextFragment.getCurrentMaxId(), "ContextFragment.nextId should increment after new fragment creation.");
    }

    @Test
    void testActionPersistenceAcrossSerializationRoundTrip() throws Exception {
        var history = new ContextHistory();
        
        // Create context with a completed action
        var projectFile = new ProjectFile(tempDir, "test.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class Test {}");
        var fragment = new ContextFragment.ProjectPathFragment(projectFile, mockContextManager);
        
        var context1 = new Context(mockContextManager, "Initial context")
                .addEditableFiles(List.of(fragment));
        history.setInitialContext(context1.freezeForTesting()); // Freeze context
        
        // Create context with a slow-resolving action (simulates async operation)
        var slowFuture = CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(1000); // 1 second delay
                return "Slow operation completed";
            } catch (InterruptedException e) {
                return "Interrupted";
            }
        });
        
        var context2 = new Context(mockContextManager, "Second context")
                .withAction(slowFuture);
        history.addFrozenContextAndClearRedo(context2.freezeForTesting());
        
        // Create context with a very slow action that should timeout
        var timeoutFuture = CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(10000); // 10 second delay - longer than 5s timeout
                return "This should timeout";
            } catch (InterruptedException e) {
                return "Interrupted";
            }
        });
        
        var context3 = new Context(mockContextManager, "Third context")
                .withAction(timeoutFuture);
        history.addFrozenContextAndClearRedo(context3.freezeForTesting());
        
        // Wait for the slow future to complete before serialization
        Thread.sleep(1500);
        
        // Serialize to ZIP
        Path zipFile = tempDir.resolve("action_persistence_test.zip");
        HistoryIo.writeZip(history, zipFile);
        
        // Deserialize from ZIP
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);
        
        // Verify we have the same number of contexts
        assertEquals(3, loadedHistory.getHistory().size());
        
        // Verify action descriptions are preserved
        var loadedContext1 = loadedHistory.getHistory().get(0);
        var loadedContext2 = loadedHistory.getHistory().get(1);
        var loadedContext3 = loadedHistory.getHistory().get(2);
        
        // First context should have the edit action preserved
        assertEquals("Edit test.java", loadedContext1.getAction());

        // Second context should have preserved the completed slow action
        assertEquals("Slow operation completed", loadedContext2.getAction());
        
        // Third context should show timeout message since it took longer than 5s
        assertEquals("(Summary Unavailable)", loadedContext3.getAction());
        
        // Verify that the actions are immediately available (completed futures)
        assertTrue(loadedContext1.action.isDone());
        assertTrue(loadedContext2.action.isDone());
        assertTrue(loadedContext3.action.isDone());
    }

    @Test
    void testSaveAndLoadSessionHistory() throws Exception {
        Project project = new Project(tempDir);
        Project.SessionInfo sessionInfo = project.newSession("History Test Session");
        UUID sessionId = sessionInfo.id();
        
        ContextHistory originalHistory = new ContextHistory();
        
        // Create dummy file
        ProjectFile dummyFile = new ProjectFile(tempDir, "dummyFile.txt");
        Files.createDirectories(dummyFile.absPath().getParent());
        Files.writeString(dummyFile.absPath(), "Dummy file content for session history test.");
        
        // Populate originalHistory
        Context context1 = new Context(mockContextManager, "Welcome to session history test.");
        originalHistory.setInitialContext(context1.freezeForTesting());
        
        ContextFragment.StringFragment sf = new ContextFragment.StringFragment(mockContextManager, "Test string fragment content", "TestSF", SyntaxConstants.SYNTAX_STYLE_NONE);
        ContextFragment.ProjectPathFragment pf = new ContextFragment.ProjectPathFragment(dummyFile, mockContextManager);
        Context context2 = new Context(mockContextManager, "Second context with fragments")
                .addVirtualFragment(sf)
                .addEditableFiles(List.of(pf));
        originalHistory.addFrozenContextAndClearRedo(context2.freezeForTesting());
        
        // Get initial modified time
        long initialModifiedTime = project.listSessions().stream()
                .filter(s -> s.id().equals(sessionId))
                .findFirst()
                .orElseThrow()
                .modified();
        
        // Save history
        project.saveHistory(originalHistory, sessionId);
        
        // Verify modified timestamp update
        List<Project.SessionInfo> updatedSessions = project.listSessions();
        Project.SessionInfo updatedSessionInfo = updatedSessions.stream()
                .filter(s -> s.id().equals(sessionId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Session not found after save"));
        assertTrue(updatedSessionInfo.modified() >= initialModifiedTime, "Modified timestamp should be updated or same (if save was very fast).");
        
        // Load history
        ContextHistory loadedHistory = project.loadHistory(sessionId, mockContextManager);
        
        // Assertions
        assertNotNull(loadedHistory, "Loaded history should not be null.");
        assertEquals(originalHistory.getHistory().size(), loadedHistory.getHistory().size(), "Number of contexts in history should match.");
        
        for (int i = 0; i < originalHistory.getHistory().size(); i++) {
            assertContextsEqual(originalHistory.getHistory().get(i), loadedHistory.getHistory().get(i));
        }
        
        project.close();
    }

    @Test
    void testProjectPathFragmentWithDifferentIdsInternToDifferentFrozenFragments() throws Exception {
        // Create a shared file
        var sharedFile = new ProjectFile(tempDir, "shared.txt");
        Files.writeString(sharedFile.absPath(), "shared content");

        // Create two ProjectPathFragments with the same file but different IDs
        var fragment1 = new ContextFragment.ProjectPathFragment(sharedFile, mockContextManager);
        var fragment2 = new ContextFragment.ProjectPathFragment(sharedFile, mockContextManager);
        
        // Verify they have different IDs
        assertNotEquals(fragment1.id(), fragment2.id(), "Fragments should have different IDs");
        
        // Freeze both fragments
        var frozen1 = FrozenFragment.freeze(fragment1, mockContextManager);
        var frozen2 = FrozenFragment.freeze(fragment2, mockContextManager);
        
        // Verify they result in different FrozenFragment instances despite same content
        assertNotSame(frozen1, frozen2, "ProjectPathFragments with different IDs should intern to different FrozenFragment instances");
        
        // Verify they have the same content hash but different IDs
        if (frozen1 instanceof FrozenFragment ff1 && frozen2 instanceof FrozenFragment ff2) {
            assertEquals(ff1.getContentHash(), ff2.getContentHash(), "Content hashes should be the same");
            assertNotEquals(ff1.id(), ff2.id(), "Frozen fragment IDs should be different");
        }
        
        // Verify both have the same description and text content
        assertEquals(frozen1.description(), frozen2.description());
        assertEquals(frozen1.text(), frozen2.text());
    }

    @Test
    void testFragmentInterningDuringDeserialization() throws IOException {
        var history = new ContextHistory();
        var projectFile = new ProjectFile(tempDir, "shared.txt");
        Files.writeString(projectFile.absPath(), "shared content");

        // Live fragments shared by both contexts
        var sharedLiveFragment  = new ContextFragment.ProjectPathFragment(projectFile, mockContextManager);
        int sharedFragmentId    = sharedLiveFragment.id();

        var liveStringFragment  = new ContextFragment.StringFragment(
                mockContextManager,
                "unique string fragment content for interning test",
                "desc",
                SyntaxConstants.SYNTAX_STYLE_NONE);
        int liveStringFragmentId = liveStringFragment.id();

        // Context 1
        var context1 = new Context(mockContextManager, "Context 1")
                .addEditableFiles(List.of(sharedLiveFragment))
                .addVirtualFragment(liveStringFragment);
        history.setInitialContext(context1.freezeForTesting());

        // Context 2
        var context2 = new Context(mockContextManager, "Context 2")
                .addEditableFiles(List.of(sharedLiveFragment))
                .addVirtualFragment(liveStringFragment);
        history.addFrozenContextAndClearRedo(context2.freezeForTesting());

        Path zipFile = tempDir.resolve("interning_test_history.zip");
        HistoryIo.writeZip(history, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertEquals(2, loadedHistory.getHistory().size());
        Context loadedCtx1 = loadedHistory.getHistory().get(0);
        Context loadedCtx2 = loadedHistory.getHistory().get(1);

        /* ---------- shared ProjectPathFragment ---------- */
        var pathFrag1 = loadedCtx1.editableFiles()
                .filter(f -> f.id() == sharedFragmentId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Shared path fragment not found in context 1"));
        var pathFrag2 = loadedCtx2.editableFiles()
                .filter(f -> f.id() == sharedFragmentId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Shared path fragment not found in context 2"));
        assertSame(pathFrag1, pathFrag2,
                   "Path fragments with the same original ID should be the same instance after deserialization.");

        /* ---------- shared String fragment (may or may not still be FrozenFragment) ---------- */
        var stringFrag1 = loadedCtx1.virtualFragments()
                .filter(f -> f.id() == liveStringFragmentId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Shared string fragment not found in context 1"));
        var stringFrag2 = loadedCtx2.virtualFragments()
                .filter(f -> f.id() == liveStringFragmentId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Shared string fragment not found in context 2"));
        assertSame(stringFrag1, stringFrag2,
                   "String fragments with the same original ID should be the same instance after deserialization.");

        /* ---------- shared TaskFragment via TaskEntry ---------- */
        var taskMessages        = List.of(UserMessage.from("User"), AiMessage.from("AI"));
        var sharedTaskFragment  = new ContextFragment.TaskFragment(mockContextManager, taskMessages, "Shared Task Log");
        int sharedTaskFragmentId = sharedTaskFragment.id();

        var origHistoryWithTask = new ContextHistory();
        var taskEntry           = new TaskEntry(1, sharedTaskFragment, null);

        var ctxWithTask1 = new Context(mockContextManager, "CtxTask1")
                .addHistoryEntry(taskEntry, sharedTaskFragment, CompletableFuture.completedFuture("action1"));
        origHistoryWithTask.setInitialContext(ctxWithTask1.freezeForTesting());

        var ctxWithTask2 = new Context(mockContextManager, "CtxTask2")
                .addHistoryEntry(taskEntry, sharedTaskFragment, CompletableFuture.completedFuture("action2"));
        origHistoryWithTask.addFrozenContextAndClearRedo(ctxWithTask2.freezeForTesting());

        Path taskZipFile = tempDir.resolve("interning_task_history.zip");
        HistoryIo.writeZip(origHistoryWithTask, taskZipFile);
        ContextHistory loadedHistoryWithTask = HistoryIo.readZip(taskZipFile, mockContextManager);

        var loadedTaskCtx1 = loadedHistoryWithTask.getHistory().get(0);
        var loadedTaskCtx2 = loadedHistoryWithTask.getHistory().get(1);

        var taskLog1 = loadedTaskCtx1.getTaskHistory().get(0).log();
        var taskLog2 = loadedTaskCtx2.getTaskHistory().get(0).log();

        assertNotNull(taskLog1);
        assertNotNull(taskLog2);
        assertEquals(sharedTaskFragmentId, taskLog1.id());
        assertEquals(sharedTaskFragmentId, taskLog2.id());
        assertSame(taskLog1, taskLog2,
                   "Shared TaskFragment logs should be the same instance after deserialization.");
    }

    @Test
    void testNewSessionCreationAndListing() throws IOException {
        // Create a Project instance using the tempDir
        Project project = new Project(tempDir);
        
        // Create first session
        Project.SessionInfo session1Info = project.newSession("Test Session 1");
        
        // Assert session1Info is valid
        assertNotNull(session1Info);
        assertEquals("Test Session 1", session1Info.name());
        assertNotNull(session1Info.id());
        
        // Verify the history zip file exists
        Path historyZip1 = tempDir.resolve(".brokk").resolve("sessions").resolve(session1Info.id() + ".zip");
        assertTrue(Files.exists(historyZip1));
        
        // Verify sessions.jsonl exists
        Path sessionsIndex = tempDir.resolve(".brokk").resolve("sessions").resolve("sessions.jsonl");
        assertTrue(Files.exists(sessionsIndex));
        
        // Read sessions.jsonl and verify its content
        List<String> lines = Files.readAllLines(sessionsIndex);
        assertEquals(1, lines.size(), "sessions.jsonl should contain exactly one line");
        
        // Deserialize the line into a SessionInfo object
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Project.SessionInfo deserializedSession = objectMapper.readValue(lines.get(0), Project.SessionInfo.class);
        
        // Assert that the deserialized SessionInfo matches session1Info
        assertEquals(session1Info.id(), deserializedSession.id());
        assertEquals(session1Info.name(), deserializedSession.name());
        assertTrue(deserializedSession.created() <= deserializedSession.modified(), "created should be <= modified");
        assertEquals(session1Info.created(), deserializedSession.created());
        assertEquals(session1Info.modified(), deserializedSession.modified());
        
        // Create second session
        Project.SessionInfo session2Info = project.newSession("Test Session 2");
        
        // List all sessions
        List<Project.SessionInfo> sessions = project.listSessions();
        
        // Assert we have 2 sessions
        assertEquals(2, sessions.size());
        
        // Verify that the list contains SessionInfo objects matching session1Info and session2Info
        var sessionIds = sessions.stream().map(Project.SessionInfo::id).collect(java.util.stream.Collectors.toSet());
        assertTrue(sessionIds.contains(session1Info.id()), "Sessions list should contain session1Info");
        assertTrue(sessionIds.contains(session2Info.id()), "Sessions list should contain session2Info");
        
        var sessionNames = sessions.stream().map(Project.SessionInfo::name).collect(java.util.stream.Collectors.toSet());
        assertTrue(sessionNames.contains("Test Session 1"), "Sessions list should contain 'Test Session 1'");
        assertTrue(sessionNames.contains("Test Session 2"), "Sessions list should contain 'Test Session 2'");
        
        // Verify both history zip files exist
        Path historyZip2 = tempDir.resolve(".brokk").resolve("sessions").resolve(session2Info.id().toString() + ".zip");
        assertTrue(Files.exists(historyZip2));
        
        // Verify sessions.jsonl now contains 2 lines
        List<String> updatedLines = Files.readAllLines(sessionsIndex);
        assertEquals(2, updatedLines.size(), "sessions.jsonl should contain exactly two lines after creating second session");
        
        project.close();
    }

    @Test
    void testRenameSession() throws IOException {
        Project project = new Project(tempDir);
        Project.SessionInfo initialSession = project.newSession("Original Name");
        
        project.renameSession(initialSession.id(), "New Name");
        
        List<Project.SessionInfo> sessions = project.listSessions();
        Project.SessionInfo renamedSession = sessions.stream()
                .filter(s -> s.id().equals(initialSession.id()))
                .findFirst()
                .orElseThrow();
        
        assertEquals("New Name", renamedSession.name());
        assertEquals(initialSession.created(), renamedSession.created()); // Created time should not change
        
        // Verify history zip still exists
        assertTrue(Files.exists(tempDir.resolve(".brokk").resolve("sessions").resolve(initialSession.id().toString() + ".zip")));
        
        project.close();
    }

    @Test
    void testDeleteSession() throws IOException {
        Project project = new Project(tempDir);
        Project.SessionInfo session1 = project.newSession("Session 1");
        Project.SessionInfo session2 = project.newSession("Session 2");
        
        UUID idToDelete = session1.id();
        Path historyFileToDelete = tempDir.resolve(".brokk").resolve("sessions").resolve(idToDelete.toString() + ".zip");
        assertTrue(Files.exists(historyFileToDelete));
        
        project.deleteSession(idToDelete);
        
        List<Project.SessionInfo> sessions = project.listSessions();
        assertEquals(1, sessions.size());
        assertEquals(session2.id(), sessions.get(0).id());
        assertFalse(Files.exists(historyFileToDelete));
        
        // Test deleting non-existent, should not throw
        project.deleteSession(UUID.randomUUID());
        
        project.close();
    }

    @Test
    void testCopySession() throws Exception {
        Project project = new Project(tempDir);
        Project.SessionInfo originalSessionInfo = project.newSession("Original Session");
        UUID originalId = originalSessionInfo.id();
        
        // Create some history content
        ContextHistory originalHistory = new ContextHistory();
        Context context = new Context(mockContextManager, "Test content");
        originalHistory.setInitialContext(context.freezeForTesting());
        project.saveHistory(originalHistory, originalId);
        
        Project.SessionInfo copiedSessionInfo = project.copySession(originalId, "Copied Session");
        
        assertNotNull(copiedSessionInfo);
        assertEquals("Copied Session", copiedSessionInfo.name());
        assertNotEquals(originalId, copiedSessionInfo.id());
        
        List<Project.SessionInfo> sessions = project.listSessions();
        assertEquals(2, sessions.size());
        assertTrue(sessions.stream().anyMatch(s -> s.id().equals(originalId)));
        assertTrue(sessions.stream().anyMatch(s -> s.id().equals(copiedSessionInfo.id())));
        
        Path copiedHistoryFile = tempDir.resolve(".brokk").resolve("sessions").resolve(copiedSessionInfo.id().toString() + ".zip");
        assertTrue(Files.exists(copiedHistoryFile));
        
        ContextHistory loadedOriginalHistory = project.loadHistory(originalId, mockContextManager);
        ContextHistory loadedCopiedHistory = project.loadHistory(copiedSessionInfo.id(), mockContextManager);
        
        assertEquals(loadedOriginalHistory.getHistory().size(), loadedCopiedHistory.getHistory().size());
        if (!loadedOriginalHistory.getHistory().isEmpty()) {
            assertContextsEqual(loadedOriginalHistory.getHistory().get(0), loadedCopiedHistory.getHistory().get(0));
        }
        
        assertTrue(copiedSessionInfo.created() <= copiedSessionInfo.modified());
        assertTrue(copiedSessionInfo.created() >= originalSessionInfo.modified()); // Copied time is 'now'
        
        project.close();
    }

    // --- Tests for individual fragment type round-trips ---

    private CodeUnit createTestCodeUnit(String fqName, ProjectFile pf) {
        String shortName = fqName.substring(fqName.lastIndexOf('.') + 1);
        String packageName = fqName.contains(".") ? fqName.substring(0, fqName.lastIndexOf('.')) : "";
        // Use CLASS as a generic kind for testing, specific kind might not be critical for serialization tests
        return new CodeUnit(pf, io.github.jbellis.brokk.analyzer.CodeUnitType.CLASS, packageName, shortName);
    }

    @Test
    void testRoundTripGitFileFragment() throws Exception {
        ContextHistory originalHistory = new ContextHistory();
        var projectFile = new ProjectFile(tempDir, "src/GitFile.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class GitFile {}");

        var fragment = new ContextFragment.GitFileFragment(projectFile, "abcdef1234567890", "content for git file");

        var context = new Context(mockContextManager, "Test GitFileFragment")
                .addReadonlyFiles(List.of(fragment));
        originalHistory.setInitialContext(context.freezeForTesting());

        Path zipFile = tempDir.resolve("test_gitfile_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        // Verify specific GitFileFragment properties after general assertion
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedFragment = (ContextFragment.GitFileFragment) loadedCtx.readonlyFiles()
                .filter(f -> f.getType() == ContextFragment.FragmentType.GIT_FILE)
                .findFirst().orElseThrow();
        assertEquals(projectFile.absPath().toString(), loadedFragment.file().absPath().toString());
        assertEquals("abcdef1234567890", loadedFragment.revision());
        assertEquals("content for git file", loadedFragment.content());
    }

    @Test
    void testRoundTripExternalPathFragment() throws Exception {
        ContextHistory originalHistory = new ContextHistory();
        Path externalFilePath = tempDir.resolve("external_file.txt");
        Files.writeString(externalFilePath, "External file content");
        var externalFile = new io.github.jbellis.brokk.analyzer.ExternalFile(externalFilePath);
        var fragment = new ContextFragment.ExternalPathFragment(externalFile, mockContextManager);

        var context = new Context(mockContextManager, "Test ExternalPathFragment")
                .addReadonlyFiles(List.of(fragment));
        originalHistory.setInitialContext(context.freezeForTesting());

        Path zipFile = tempDir.resolve("test_externalpath_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedRawFragment = loadedCtx.readonlyFiles()
                .filter(f -> f.getType() == ContextFragment.FragmentType.EXTERNAL_PATH) // getType on FrozenFragment returns originalType
                .findFirst().orElseThrow();
        
        assertTrue(loadedRawFragment instanceof FrozenFragment, "ExternalPathFragment should be loaded as FrozenFragment");
        var loadedFrozenFragment = (FrozenFragment) loadedRawFragment;
        assertEquals(ContextFragment.FragmentType.EXTERNAL_PATH, loadedFrozenFragment.getType());
        assertEquals(ContextFragment.ExternalPathFragment.class.getName(), loadedFrozenFragment.originalClassName());
        assertEquals(externalFilePath.toString(), loadedFrozenFragment.meta().get("absPath"));
    }

    @Test
    void testRoundTripImageFileFragment() throws Exception {
        ContextHistory originalHistory = new ContextHistory();
        Path imageFilePath = tempDir.resolve("test_image.png");
        var testImage = createTestImage(Color.GREEN, 20, 20);
        ImageIO.write(testImage, "PNG", imageFilePath.toFile());
        var brokkImageFile = new io.github.jbellis.brokk.analyzer.ProjectFile(tempDir, tempDir.relativize(imageFilePath)); // Treat as project file for test
        var fragment = new ContextFragment.ImageFileFragment(brokkImageFile, mockContextManager);

        var context = new Context(mockContextManager, "Test ImageFileFragment")
                .addReadonlyFiles(List.of(fragment));
        originalHistory.setInitialContext(context.freezeForTesting());

        Path zipFile = tempDir.resolve("test_imagefile_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedRawFragment = loadedCtx.readonlyFiles()
                .filter(f -> f.getType() == ContextFragment.FragmentType.IMAGE_FILE) // getType on FrozenFragment returns originalType
                .findFirst().orElseThrow();

        assertTrue(loadedRawFragment instanceof FrozenFragment, "ImageFileFragment should be loaded as FrozenFragment");
        var loadedFrozenFragment = (FrozenFragment) loadedRawFragment;
        assertEquals(ContextFragment.FragmentType.IMAGE_FILE, loadedFrozenFragment.getType());
        assertEquals(ContextFragment.ImageFileFragment.class.getName(), loadedFrozenFragment.originalClassName());
        
        // Check path from meta
        String loadedAbsPath = loadedFrozenFragment.meta().get("absPath");
        assertNotNull(loadedAbsPath, "absPath not found in FrozenFragment meta for ImageFileFragment");
        assertEquals(imageFilePath.toString(), loadedAbsPath);

        // Check image content from bytes
        byte[] imageBytes = loadedFrozenFragment.imageBytesContent();
        assertNotNull(imageBytes, "Image bytes not found in FrozenFragment for ImageFileFragment");
        Image loadedImageFromBytes = ImageIO.read(new java.io.ByteArrayInputStream(imageBytes));
        assertNotNull(loadedImageFromBytes);
        assertEquals(20, loadedImageFromBytes.getWidth(null));
        assertEquals(20, loadedImageFromBytes.getHeight(null));
    }

    @Test
    void testRoundTripSearchFragment() throws Exception {
        ContextHistory originalHistory = new ContextHistory();
        var projectFile = new ProjectFile(tempDir, "src/SearchTarget.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class SearchTarget {}");
        var codeUnit = createTestCodeUnit("com.example.SearchTarget", projectFile);
        var messages = List.of(UserMessage.from("user query"), AiMessage.from("ai response"));
        var fragment = new ContextFragment.SearchFragment(mockContextManager, "Search: foobar", messages, Set.of(codeUnit));

        var context = new Context(mockContextManager, "Test SearchFragment")
                .addVirtualFragment(fragment);
        originalHistory.setInitialContext(context.freezeForTesting());

        Path zipFile = tempDir.resolve("test_search_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedFragment = (ContextFragment.SearchFragment) loadedCtx.virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.SEARCH)
                .findFirst().orElseThrow();
        assertEquals("Search: foobar", loadedFragment.description());
        assertEquals(2, loadedFragment.messages().size());
        assertEquals(1, loadedFragment.sources().size());
        assertEquals(codeUnit.fqName(), loadedFragment.sources().iterator().next().fqName());
    }

    @Test
    void testRoundTripSkeletonFragment() throws Exception {
        ContextHistory originalHistory = new ContextHistory();
        var targetIds = List.of("com.example.ClassA", "com.example.ClassB");
        var fragment = new ContextFragment.SkeletonFragment(mockContextManager, targetIds, ContextFragment.SummaryType.CLASS_SKELETON);

        var context = new Context(mockContextManager, "Test SkeletonFragment")
                .addVirtualFragment(fragment);
        originalHistory.setInitialContext(context.freezeForTesting());

        Path zipFile = tempDir.resolve("test_skeleton_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedRawFragment = loadedCtx.virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.SKELETON)
                .findFirst().orElseThrow();
        
        if (loadedRawFragment instanceof ContextFragment.SkeletonFragment loadedFragment) {
            assertEquals(targetIds, loadedFragment.getTargetIdentifiers());
            assertEquals(ContextFragment.SummaryType.CLASS_SKELETON, loadedFragment.getSummaryType());
        } else if (loadedRawFragment instanceof FrozenFragment loadedFrozenFragment) {
            assertEquals(ContextFragment.FragmentType.SKELETON, loadedFrozenFragment.getType());
            assertEquals(ContextFragment.SkeletonFragment.class.getName(), loadedFrozenFragment.originalClassName());
            assertEquals(String.join(";", targetIds), loadedFrozenFragment.meta().get("targetIdentifiers"));
            assertEquals(ContextFragment.SummaryType.CLASS_SKELETON.name(), loadedFrozenFragment.meta().get("summaryType"));
        } else {
            fail("Expected SkeletonFragment or FrozenFragment, got: " + loadedRawFragment.getClass());
        }
    }

    @Test
    void testRoundTripUsageFragment() throws Exception {
        ContextHistory originalHistory = new ContextHistory();
        var fragment = new ContextFragment.UsageFragment(mockContextManager, "com.example.MyClass.myMethod");

        var context = new Context(mockContextManager, "Test UsageFragment")
                .addVirtualFragment(fragment);
        originalHistory.setInitialContext(context.freezeForTesting());

        Path zipFile = tempDir.resolve("test_usage_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedRawFragment = loadedCtx.virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.USAGE)
                .findFirst().orElseThrow();
        
        if (loadedRawFragment instanceof ContextFragment.UsageFragment loadedFragment) {
            assertEquals("com.example.MyClass.myMethod", loadedFragment.targetIdentifier());
        } else if (loadedRawFragment instanceof FrozenFragment loadedFrozenFragment) {
            assertEquals(ContextFragment.FragmentType.USAGE, loadedFrozenFragment.getType());
            assertEquals(ContextFragment.UsageFragment.class.getName(), loadedFrozenFragment.originalClassName());
            assertEquals("com.example.MyClass.myMethod", loadedFrozenFragment.meta().get("targetIdentifier"));
        } else {
            fail("Expected UsageFragment or FrozenFragment, got: " + loadedRawFragment.getClass());
        }
    }

    @Test
    void testRoundTripCallGraphFragment() throws Exception {
        ContextHistory originalHistory = new ContextHistory();
        var fragment = new ContextFragment.CallGraphFragment(mockContextManager, "com.example.MyClass.doStuff", 3, true);

        var context = new Context(mockContextManager, "Test CallGraphFragment")
                .addVirtualFragment(fragment);
        originalHistory.setInitialContext(context.freezeForTesting());

        Path zipFile = tempDir.resolve("test_callgraph_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedRawFragment = loadedCtx.virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.CALL_GRAPH)
                .findFirst().orElseThrow();
        
        if (loadedRawFragment instanceof ContextFragment.CallGraphFragment loadedFragment) {
            assertEquals("com.example.MyClass.doStuff", loadedFragment.getMethodName());
            assertEquals(3, loadedFragment.getDepth());
            assertTrue(loadedFragment.isCalleeGraph());
        } else if (loadedRawFragment instanceof FrozenFragment loadedFrozenFragment) {
            assertEquals(ContextFragment.FragmentType.CALL_GRAPH, loadedFrozenFragment.getType());
            assertEquals(ContextFragment.CallGraphFragment.class.getName(), loadedFrozenFragment.originalClassName());
            assertEquals("com.example.MyClass.doStuff", loadedFrozenFragment.meta().get("methodName"));
            assertEquals("3", loadedFrozenFragment.meta().get("depth"));
            assertEquals("true", loadedFrozenFragment.meta().get("isCalleeGraph"));
        } else {
            fail("Expected CallGraphFragment or FrozenFragment, got: " + loadedRawFragment.getClass());
        }
    }

    @Test
    void testRoundTripHistoryFragment() throws Exception {
        ContextHistory originalHistory = new ContextHistory();
        var taskMessages = List.<ChatMessage>of(UserMessage.from("Task user"), AiMessage.from("Task AI"));
        var taskFragment = new ContextFragment.TaskFragment(mockContextManager, taskMessages, "Test Task Log");
        var taskEntry = new TaskEntry(1, taskFragment, null);
        var fragment = new ContextFragment.HistoryFragment(mockContextManager, List.of(taskEntry));

        var context = new Context(mockContextManager, "Test HistoryFragment")
                .addVirtualFragment(fragment);
        originalHistory.setInitialContext(context.freezeForTesting());

        Path zipFile = tempDir.resolve("test_history_frag_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedFragment = (ContextFragment.HistoryFragment) loadedCtx.virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.HISTORY)
                .findFirst().orElseThrow();
        assertEquals(1, loadedFragment.entries().size());
        assertTaskEntriesEqual(taskEntry, loadedFragment.entries().get(0));
    }

    @Test
    void testRoundTripPasteTextFragment() throws Exception {
        ContextHistory originalHistory = new ContextHistory();
        var fragment = new ContextFragment.PasteTextFragment(mockContextManager, "Pasted text content", CompletableFuture.completedFuture("Pasted text summary"));

        var context = new Context(mockContextManager, "Test PasteTextFragment")
                .addVirtualFragment(fragment);
        originalHistory.setInitialContext(context.freezeForTesting());

        Path zipFile = tempDir.resolve("test_pastetext_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedFragment = (ContextFragment.PasteTextFragment) loadedCtx.virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.PASTE_TEXT)
                .findFirst().orElseThrow();
        assertEquals("Pasted text content", loadedFragment.text());
        assertEquals("Paste of Pasted text summary", loadedFragment.description());
    }

    @Test
    void testRoundTripStacktraceFragment() throws Exception {
        ContextHistory originalHistory = new ContextHistory();
        var projectFile = new ProjectFile(tempDir, "src/ErrorSource.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class ErrorSource {}");
        var codeUnit = createTestCodeUnit("com.example.ErrorSource", projectFile);
        var fragment = new ContextFragment.StacktraceFragment(mockContextManager, Set.of(codeUnit),
                                                              "Full stacktrace original text",
                                                              "NullPointerException",
                                                              "ErrorSource.java:10");

        var context = new Context(mockContextManager, "Test StacktraceFragment")
                .addVirtualFragment(fragment);
        originalHistory.setInitialContext(context.freezeForTesting());

        Path zipFile = tempDir.resolve("test_stacktrace_history.zip");
        HistoryIo.writeZip(originalHistory, zipFile);
        ContextHistory loadedHistory = HistoryIo.readZip(zipFile, mockContextManager);

        assertContextsEqual(originalHistory.getHistory().get(0), loadedHistory.getHistory().get(0));
        Context loadedCtx = loadedHistory.getHistory().get(0);
        var loadedFragment = (ContextFragment.StacktraceFragment) loadedCtx.virtualFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.STACKTRACE)
                .findFirst().orElseThrow();
        assertEquals("stacktrace of NullPointerException", loadedFragment.description());
        assertTrue(loadedFragment.text().contains("Full stacktrace original text"));
        assertTrue(loadedFragment.text().contains("ErrorSource.java:10"));
        assertEquals(1, loadedFragment.sources().size());
        assertEquals(codeUnit.fqName(), loadedFragment.sources().iterator().next().fqName());
    }
}
