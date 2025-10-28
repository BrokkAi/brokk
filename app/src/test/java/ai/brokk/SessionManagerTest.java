package ai.brokk;

import static ai.brokk.SessionManager.SessionInfo;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextHistory;
import ai.brokk.context.FrozenFragment;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.util.Messages;
import dev.langchain4j.data.message.ChatMessage;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SessionManagerTest {
    @TempDir
    Path tempDir;

    private IContextManager mockContextManager;

    @BeforeEach
    void setup() throws IOException {
        mockContextManager = new TestContextManager(tempDir, new NoOpConsoleIO());
        // Clear the intern pool before each test to ensure isolation
        FrozenFragment.clearInternPoolForTesting();
        // Reset fragment ID counter for test isolation
        ContextFragment.nextId.set(1);

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

    private byte[] imageToBytes(Image image) throws IOException {
        if (image == null) return null;
        BufferedImage bufferedImage = (image instanceof BufferedImage)
                ? (BufferedImage) image
                : new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
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

    private void assertEventually(Runnable assertion) throws InterruptedException {
        long timeout = 5000; // 5 seconds
        long interval = 100; // 100 ms
        long startTime = System.currentTimeMillis();
        while (true) {
            try {
                assertion.run();
                return; // success
            } catch (AssertionError e) {
                if (System.currentTimeMillis() - startTime >= timeout) {
                    throw e;
                }
                // ignore and retry
            }
            Thread.sleep(interval);
        }
    }

    @Test
    void testSaveAndLoadSessionHistory() throws Exception {
        MainProject project = new MainProject(tempDir);
        var sessionManager = project.getSessionManager();
        SessionInfo sessionInfo = sessionManager.newSession("History Test Session");
        UUID sessionId = sessionInfo.id();

        var initialContext = new Context(mockContextManager, "Welcome to session history test.");
        ContextHistory originalHistory = new ContextHistory(initialContext);

        // Create dummy file
        ProjectFile dummyFile = new ProjectFile(tempDir, "dummyFile.txt");
        Files.createDirectories(dummyFile.absPath().getParent());
        Files.writeString(dummyFile.absPath(), "Dummy file content for session history test.");

        // Populate originalHistory

        ContextFragment.StringFragment sf = new ContextFragment.StringFragment(
                mockContextManager, "Test string fragment content", "TestSF", SyntaxConstants.SYNTAX_STYLE_NONE);
        ContextFragment.ProjectPathFragment pf = new ContextFragment.ProjectPathFragment(dummyFile, mockContextManager);
        Context context2 = new Context(mockContextManager, "Second context with fragments")
                .addVirtualFragment(sf)
                .addPathFragments(List.of(pf));
        originalHistory.addFrozenContextAndClearRedo(context2.freeze());

        // Get initial modified time
        long initialModifiedTime = sessionManager.listSessions().stream()
                .filter(s -> s.id().equals(sessionId))
                .findFirst()
                .orElseThrow()
                .modified();

        // Save history
        sessionManager.saveHistory(originalHistory, sessionId);

        // --- Verification with live session manager (cached sessions) ---
        verifySessionHistory(
                sessionManager, sessionId, initialModifiedTime, originalHistory, "after save (cached sessions)");
        project.close();

        // --- Verification with new session manager (sessions loaded from disk) ---
        MainProject newProject = new MainProject(tempDir);
        verifySessionHistory(
                newProject.getSessionManager(),
                sessionId,
                initialModifiedTime,
                originalHistory,
                "after recreating project (sessions loaded from disk)");
        newProject.close();
    }

    private void verifySessionHistory(
            SessionManager sessionManager,
            UUID sessionId,
            long initialModifiedTime,
            ContextHistory originalHistory,
            String verificationPhaseMessage)
            throws IOException, InterruptedException {
        // Verify modified timestamp update
        List<SessionInfo> updatedSessions = sessionManager.listSessions();
        SessionInfo updatedSessionInfo = updatedSessions.stream()
                .filter(s -> s.id().equals(sessionId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Session not found " + verificationPhaseMessage));
        assertTrue(
                updatedSessionInfo.modified() >= initialModifiedTime,
                "Modified timestamp should be updated or same. Verification phase: " + verificationPhaseMessage);

        // Load history
        ContextHistory loadedHistory = sessionManager.loadHistory(sessionId, mockContextManager);

        // Assertions
        assertNotNull(
                loadedHistory, "Loaded history should not be null. Verification phase: " + verificationPhaseMessage);
        assertEquals(
                originalHistory.getHistory().size(),
                loadedHistory.getHistory().size(),
                "Number of contexts in history should match. Verification phase: " + verificationPhaseMessage);

        for (int i = 0; i < originalHistory.getHistory().size(); i++) {
            assertContextsEqual(
                    originalHistory.getHistory().get(i),
                    loadedHistory.getHistory().get(i));
        }
    }

    private void assertContextsEqual(Context expected, Context actual) throws IOException, InterruptedException {
        // Compare editable files
        var expectedEditable = expected.fileFragments()
                .sorted(Comparator.comparing(ContextFragment::id))
                .toList();
        var actualEditable = actual.fileFragments()
                .sorted(Comparator.comparing(ContextFragment::id))
                .toList();
        assertEquals(expectedEditable.size(), actualEditable.size(), "Editable files count mismatch");
        for (int i = 0; i < expectedEditable.size(); i++) {
            assertContextFragmentsEqual(expectedEditable.get(i), actualEditable.get(i));
        }

        // Compare virtual fragments
        var expectedVirtuals = expected.virtualFragments()
                .sorted(Comparator.comparing(ContextFragment::id))
                .toList();
        var actualVirtuals = actual.virtualFragments()
                .sorted(Comparator.comparing(ContextFragment::id))
                .toList();
        assertEquals(expectedVirtuals.size(), actualVirtuals.size(), "Virtual fragments count mismatch");
        for (int i = 0; i < expectedVirtuals.size(); i++) {
            assertContextFragmentsEqual(expectedVirtuals.get(i), actualVirtuals.get(i));
        }

        // Compare task history
        assertEquals(expected.getTaskHistory().size(), actual.getTaskHistory().size(), "Task history size mismatch");
        for (int i = 0; i < expected.getTaskHistory().size(); i++) {
            assertTaskEntriesEqual(
                    expected.getTaskHistory().get(i), actual.getTaskHistory().get(i));
        }
    }

    private void assertContextFragmentsEqual(ContextFragment expected, ContextFragment actual)
            throws IOException, InterruptedException {
        assertEquals(expected.id(), actual.id(), "Fragment ID mismatch");
        assertEquals(expected.getType(), actual.getType(), "Fragment type mismatch for ID " + expected.id());
        assertEquals(
                expected.description(), actual.description(), "Fragment description mismatch for ID " + expected.id());
        assertEquals(
                expected.shortDescription(),
                actual.shortDescription(),
                "Fragment shortDescription mismatch for ID " + expected.id());
        assertEquals(expected.isText(), actual.isText(), "Fragment isText mismatch for ID " + expected.id());
        assertEquals(
                expected.syntaxStyle(), actual.syntaxStyle(), "Fragment syntaxStyle mismatch for ID " + expected.id());

        if (expected.isText()) {
            assertEquals(expected.text(), actual.text(), "Fragment text content mismatch for ID " + expected.id());
        } else {
            // For image fragments, compare byte content if both are FrozenFragment or can provide bytes
            if (expected instanceof FrozenFragment expectedFf && actual instanceof FrozenFragment actualFf) {
                assertArrayEquals(
                        expectedFf.imageBytesContent(),
                        actualFf.imageBytesContent(),
                        "FrozenFragment imageBytesContent mismatch for ID " + expected.id());
            } else {
                if (actual.image() != null) { // Fallback for non-frozen, if any after freezing
                    assertArrayEquals(
                            imageToBytes(expected.image()),
                            imageToBytes(actual.image()),
                            "Fragment image content mismatch for ID " + expected.id());
                }
            }
        }

        // Compare additional serialized top-level methods
        assertEquals(
                expected.description(),
                actual.description(),
                "Fragment formatSummary mismatch for ID " + expected.id());
        assertEquals(expected.repr(), actual.repr(), "Fragment repr mismatch for ID " + expected.id());

        // Compare files and sources (ProjectFile and CodeUnit DTOs are by value)
        if (!(expected instanceof FrozenFragment) && !(actual instanceof FrozenFragment)) {
            assertEquals(
                    expected.sources().stream().map(CodeUnit::fqName).collect(Collectors.toSet()),
                    actual.sources().stream().map(CodeUnit::fqName).collect(Collectors.toSet()),
                    "Fragment sources mismatch for ID " + expected.id());
        }
        assertEquals(
                expected.files().stream().map(ProjectFile::toString).collect(Collectors.toSet()),
                actual.files().stream().map(ProjectFile::toString).collect(Collectors.toSet()),
                "Fragment files mismatch for ID " + expected.id());

        if (expected instanceof FrozenFragment expectedFf && actual instanceof FrozenFragment actualFf) {
            assertEquals(
                    expectedFf.originalClassName(),
                    actualFf.originalClassName(),
                    "FrozenFragment originalClassName mismatch for ID " + expected.id());
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
            assertEquals(
                    expected.log().messages().size(), actual.log().messages().size());
            for (int i = 0; i < expected.log().messages().size(); i++) {
                ChatMessage expectedMsg = expected.log().messages().get(i);
                ChatMessage actualMsg = actual.log().messages().get(i);
                assertEquals(expectedMsg.type(), actualMsg.type());
                assertEquals(Messages.getRepr(expectedMsg), Messages.getRepr(actualMsg));
            }
        }
    }

    @Test
    void testNewSessionCreationAndListing() throws Exception {
        // Create a Project instance using the tempDir
        MainProject project = new MainProject(tempDir);
        var sessionManager = project.getSessionManager();

        // Create first session
        SessionInfo session1Info = sessionManager.newSession("Test Session 1");

        // Assert session1Info is valid
        assertNotNull(session1Info);
        assertEquals("Test Session 1", session1Info.name());
        assertNotNull(session1Info.id());

        // Verify the history zip file exists
        Path historyZip1 = tempDir.resolve(".brokk").resolve("sessions").resolve(session1Info.id() + ".zip");
        assertEventually(() -> assertTrue(Files.exists(historyZip1)));

        // List sessions and verify session1Info
        List<SessionInfo> sessionsAfter1 = sessionManager.listSessions();
        assertEquals(1, sessionsAfter1.size(), "Should be 1 session after creating the first.");
        SessionInfo listedSession1 = sessionsAfter1.get(0);
        assertEquals(session1Info.id(), listedSession1.id());
        assertEquals(session1Info.name(), listedSession1.name());
        assertEquals(session1Info.created(), listedSession1.created());
        assertEquals(session1Info.modified(), listedSession1.modified());
        assertTrue(listedSession1.created() <= listedSession1.modified(), "created should be <= modified for session1");

        // Create second session
        SessionInfo session2Info = sessionManager.newSession("Test Session 2");
        assertNotNull(session2Info);
        Path historyZip2 = tempDir.resolve(".brokk")
                .resolve("sessions")
                .resolve(session2Info.id().toString() + ".zip");
        assertEventually(() -> assertTrue(Files.exists(historyZip2)));

        // List all sessions
        List<SessionInfo> sessionsAfter2 = sessionManager.listSessions();

        // Assert we have 2 sessions
        assertEquals(2, sessionsAfter2.size(), "Should be 2 sessions after creating the second.");

        // Verify that the list contains SessionInfo objects matching session1Info and session2Info
        var sessionMap = sessionsAfter2.stream().collect(Collectors.toMap(SessionInfo::id, s -> s));

        assertTrue(sessionMap.containsKey(session1Info.id()), "Sessions list should contain session1Info by ID");
        SessionInfo foundSession1 = sessionMap.get(session1Info.id());
        assertEquals("Test Session 1", foundSession1.name());

        assertTrue(sessionMap.containsKey(session2Info.id()), "Sessions list should contain session2Info by ID");
        SessionInfo foundSession2 = sessionMap.get(session2Info.id());
        assertEquals("Test Session 2", foundSession2.name());
        assertTrue(foundSession2.created() <= foundSession2.modified(), "created should be <= modified for session2");

        project.close();
    }

    @Test
    void testRenameSession() throws Exception {
        MainProject project = new MainProject(tempDir);
        var sessionManager = project.getSessionManager();
        SessionInfo initialSession = sessionManager.newSession("Original Name");

        sessionManager.renameSession(initialSession.id(), "New Name");

        List<SessionInfo> sessions = sessionManager.listSessions();
        SessionInfo renamedSession = sessions.stream()
                .filter(s -> s.id().equals(initialSession.id()))
                .findFirst()
                .orElseThrow();

        assertEquals("New Name", renamedSession.name());
        assertEquals(initialSession.created(), renamedSession.created()); // Created time should not change

        // Verify history zip still exists
        assertEventually(() -> assertTrue(Files.exists(tempDir.resolve(".brokk")
                .resolve("sessions")
                .resolve(initialSession.id().toString() + ".zip"))));

        project.close();
    }

    @Test
    void testDeleteSession() throws Exception {
        MainProject project = new MainProject(tempDir);
        var sessionManager = project.getSessionManager();
        SessionInfo session1 = sessionManager.newSession("Session 1");
        SessionInfo session2 = sessionManager.newSession("Session 2");

        UUID idToDelete = session1.id();
        Path historyFileToDelete =
                tempDir.resolve(".brokk").resolve("sessions").resolve(idToDelete.toString() + ".zip");
        assertEventually(() -> assertTrue(Files.exists(historyFileToDelete)));

        sessionManager.deleteSession(idToDelete);

        List<SessionInfo> sessions = sessionManager.listSessions();
        assertEquals(1, sessions.size());
        assertEquals(session2.id(), sessions.get(0).id());
        assertFalse(Files.exists(historyFileToDelete));

        // Test deleting non-existent, should not throw
        sessionManager.deleteSession(SessionManager.newSessionId());

        project.close();
    }

    @Test
    void testCopySession() throws Exception {
        MainProject project = new MainProject(tempDir);
        var sessionManager = project.getSessionManager();
        SessionInfo originalSessionInfo = sessionManager.newSession("Original Session");
        UUID originalId = originalSessionInfo.id();

        var originalHistoryFile = tempDir.resolve(".brokk").resolve("sessions").resolve(originalId.toString() + ".zip");
        assertEventually(() -> assertTrue(Files.exists(originalHistoryFile)));

        // Create some history content
        Context context = new Context(mockContextManager, "Test content");
        ContextHistory originalHistory = new ContextHistory(context);
        sessionManager.saveHistory(originalHistory, originalId);

        SessionInfo copiedSessionInfo = sessionManager.copySession(originalId, "Copied Session");

        assertNotNull(copiedSessionInfo);
        assertEquals("Copied Session", copiedSessionInfo.name());
        assertNotEquals(originalId, copiedSessionInfo.id());

        List<SessionInfo> sessions = sessionManager.listSessions();
        assertEquals(2, sessions.size());
        assertTrue(sessions.stream().anyMatch(s -> s.id().equals(originalId)));
        assertTrue(sessions.stream().anyMatch(s -> s.id().equals(copiedSessionInfo.id())));

        Path copiedHistoryFile = tempDir.resolve(".brokk")
                .resolve("sessions")
                .resolve(copiedSessionInfo.id().toString() + ".zip");
        assertEventually(() -> assertTrue(Files.exists(copiedHistoryFile)));

        ContextHistory loadedOriginalHistory = sessionManager.loadHistory(originalId, mockContextManager);
        ContextHistory loadedCopiedHistory = sessionManager.loadHistory(copiedSessionInfo.id(), mockContextManager);

        assertEquals(
                loadedOriginalHistory.getHistory().size(),
                loadedCopiedHistory.getHistory().size());
        if (!loadedOriginalHistory.getHistory().isEmpty()) {
            assertContextsEqual(
                    loadedOriginalHistory.getHistory().get(0),
                    loadedCopiedHistory.getHistory().get(0));
        }

        assertTrue(copiedSessionInfo.created() <= copiedSessionInfo.modified());
        assertTrue(copiedSessionInfo.created() >= originalSessionInfo.modified()); // Copied time is 'now'

        project.close();
    }
}
