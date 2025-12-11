package ai.brokk;

import static ai.brokk.SessionManager.SessionInfo;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextHistory;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.util.Messages;
import com.fasterxml.jackson.core.JsonProcessingException;
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
        // Reset fragment ID counter for test isolation
        ContextFragment.setMinimumId(1);

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

        var initialContext = new Context(mockContextManager);
        ContextHistory originalHistory = new ContextHistory(initialContext);

        // Create dummy file
        ProjectFile dummyFile = new ProjectFile(tempDir, "dummyFile.txt");
        Files.createDirectories(dummyFile.absPath().getParent());
        Files.writeString(dummyFile.absPath(), "Dummy file content for session history test.");

        // Populate originalHistory

        ContextFragment.StringFragment sf = new ContextFragment.StringFragment(
                mockContextManager, "Test string fragment content", "TestSF", SyntaxConstants.SYNTAX_STYLE_NONE);
        ContextFragment.ProjectPathFragment pf = new ContextFragment.ProjectPathFragment(dummyFile, mockContextManager);
        Context context2 = new Context(mockContextManager).addFragments(List.of(sf, pf));
        originalHistory.pushContext(context2);

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
                expected.description().join(),
                actual.description().join(),
                "Fragment description mismatch for ID " + expected.id());
        assertEquals(
                expected.shortDescription().join(),
                actual.shortDescription().join(),
                "Fragment shortDescription mismatch for ID " + expected.id());
        assertEquals(expected.isText(), actual.isText(), "Fragment isText mismatch for ID " + expected.id());
        assertEquals(
                expected.syntaxStyle().join(),
                actual.syntaxStyle().join(),
                "Fragment syntaxStyle mismatch for ID " + expected.id());

        if (expected.isText()) {
            assertEquals(
                    expected.text().join(),
                    actual.text().join(),
                    "Fragment text content mismatch for ID " + expected.id());
        } else {
            // Compare image content via the common API
            assertArrayEquals(
                    expected.imageBytes().join(),
                    actual.imageBytes().join(),
                    "Fragment image content mismatch for ID " + expected.id());
        }

        // Compare additional serialized top-level methods
        assertEquals(
                expected.description().join(),
                actual.description().join(),
                "Fragment formatSummary mismatch for ID " + expected.id());
        assertEquals(expected.repr(), actual.repr(), "Fragment repr mismatch for ID " + expected.id());

        // Compare files and sources (ProjectFile and CodeUnit DTOs are by value)
        assertEquals(
                expected.sources().join().stream().map(CodeUnit::fqName).collect(Collectors.toSet()),
                actual.sources().join().stream().map(CodeUnit::fqName).collect(Collectors.toSet()),
                "Fragment sources mismatch for ID " + expected.id());
        assertEquals(
                expected.files().join().stream().map(ProjectFile::toString).collect(Collectors.toSet()),
                actual.files().join().stream().map(ProjectFile::toString).collect(Collectors.toSet()),
                "Fragment files mismatch for ID " + expected.id());
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
        Context context = new Context(mockContextManager);
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

    @Test
    void testNewSessionHasUnknownAiResponseCount() throws Exception {
        MainProject project = new MainProject(tempDir);
        var sessionManager = project.getSessionManager();

        SessionInfo sessionInfo = sessionManager.newSession("Test Session");

        assertEquals(
                SessionInfo.COUNT_UNKNOWN,
                sessionInfo.aiResponseCount(),
                "New session should have COUNT_UNKNOWN for aiResponseCount");

        project.close();
    }

    @Test
    void testSaveHistoryUpdatesAiResponseCount() throws Exception {
        MainProject project = new MainProject(tempDir);
        var sessionManager = project.getSessionManager();
        SessionInfo sessionInfo = sessionManager.newSession("Test Session");
        UUID sessionId = sessionInfo.id();

        // Create history with AI responses
        var initialContext = new Context(mockContextManager);
        ContextHistory history = new ContextHistory(initialContext);

        // Add a context with AI result (TaskFragment with AI message)
        var aiMessages = List.<ChatMessage>of(
                dev.langchain4j.data.message.UserMessage.from("User question"),
                dev.langchain4j.data.message.AiMessage.from("AI response"));
        var taskFragment = new ContextFragment.TaskFragment(mockContextManager, aiMessages, "test-task");
        var aiContext = new Context(mockContextManager).withParsedOutput(taskFragment, "AI completed task");
        history.pushContext(aiContext);

        // Add another AI response
        var aiMessages2 = List.<ChatMessage>of(
                dev.langchain4j.data.message.UserMessage.from("Another question"),
                dev.langchain4j.data.message.AiMessage.from("Another response"));
        var taskFragment2 = new ContextFragment.TaskFragment(mockContextManager, aiMessages2, "test-task-2");
        var aiContext2 = new Context(mockContextManager).withParsedOutput(taskFragment2, "AI completed another task");
        history.pushContext(aiContext2);

        // Save and wait for async write
        sessionManager.saveHistory(history, sessionId);

        // Verify count is updated in cache
        assertEventually(() -> {
            var sessions = sessionManager.listSessions();
            var updated = sessions.stream()
                    .filter(s -> s.id().equals(sessionId))
                    .findFirst()
                    .orElseThrow();
            assertEquals(
                    2,
                    updated.aiResponseCount(),
                    "aiResponseCount should be 2 after saving history with 2 AI responses");
        });

        // Small delay to allow async write to be submitted before close() awaits termination
        Thread.sleep(100);
        project.close();

        // Verify persisted to disk by reopening
        MainProject newProject = new MainProject(tempDir);
        var newSessionManager = newProject.getSessionManager();
        var reloadedSessions = newSessionManager.listSessions();
        var reloadedSession = reloadedSessions.stream()
                .filter(s -> s.id().equals(sessionId))
                .findFirst()
                .orElseThrow();

        assertEquals(2, reloadedSession.aiResponseCount(), "aiResponseCount should persist after reopening project");

        newProject.close();
    }

    @Test
    void testCopySessionPreservesAiResponseCount() throws Exception {
        MainProject project = new MainProject(tempDir);
        var sessionManager = project.getSessionManager();
        SessionInfo originalSession = sessionManager.newSession("Original");
        UUID originalId = originalSession.id();

        // Create history with 1 AI response and save
        var initialContext = new Context(mockContextManager);
        ContextHistory history = new ContextHistory(initialContext);
        var aiMessages = List.<ChatMessage>of(
                dev.langchain4j.data.message.UserMessage.from("Question"),
                dev.langchain4j.data.message.AiMessage.from("Answer"));
        var taskFragment = new ContextFragment.TaskFragment(mockContextManager, aiMessages, "task");
        var aiContext = new Context(mockContextManager).withParsedOutput(taskFragment, "Completed");
        history.pushContext(aiContext);
        sessionManager.saveHistory(history, originalId);

        // Wait for count to be updated
        assertEventually(() -> {
            var sessions = sessionManager.listSessions();
            var updated = sessions.stream()
                    .filter(s -> s.id().equals(originalId))
                    .findFirst()
                    .orElseThrow();
            assertEquals(1, updated.aiResponseCount());
        });

        // Copy session
        SessionInfo copiedSession = sessionManager.copySession(originalId, "Copied");

        assertEquals(
                1, copiedSession.aiResponseCount(), "Copied session should preserve aiResponseCount from original");

        project.close();
    }

    // JSON serialization/deserialization tests for SessionInfo

    @Test
    void testSessionInfoDeserializationWithoutAiResponseCount() throws JsonProcessingException {
        // Old JSON format without aiResponseCount field
        String oldJson =
                """
            {"id":"550e8400-e29b-41d4-a716-446655440000","name":"Old Session","created":1000,"modified":2000}
            """;

        SessionInfo info = AbstractProject.objectMapper.readValue(oldJson, SessionInfo.class);

        assertEquals(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), info.id());
        assertEquals("Old Session", info.name());
        assertEquals(1000L, info.created());
        assertEquals(2000L, info.modified());
        assertEquals(
                SessionInfo.COUNT_UNKNOWN,
                info.aiResponseCount(),
                "Missing aiResponseCount should deserialize to COUNT_UNKNOWN (-1)");
    }

    @Test
    void testSessionInfoDeserializationWithAiResponseCount() throws JsonProcessingException {
        // New JSON format with aiResponseCount field
        String newJson =
                """
            {"id":"550e8400-e29b-41d4-a716-446655440000","name":"New Session","created":1000,"modified":2000,"aiResponseCount":42}
            """;

        SessionInfo info = AbstractProject.objectMapper.readValue(newJson, SessionInfo.class);

        assertEquals(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), info.id());
        assertEquals("New Session", info.name());
        assertEquals(1000L, info.created());
        assertEquals(2000L, info.modified());
        assertEquals(42, info.aiResponseCount(), "aiResponseCount should preserve exact value from JSON");
    }

    @Test
    void testSessionInfoRoundTrip() throws JsonProcessingException {
        // Create a SessionInfo with all fields
        var original = new SessionInfo(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), "Round Trip Session", 1000L, 2000L, 17);

        // Serialize to JSON
        String json = AbstractProject.objectMapper.writeValueAsString(original);

        // Deserialize back
        SessionInfo restored = AbstractProject.objectMapper.readValue(json, SessionInfo.class);

        // Verify all fields are preserved
        assertEquals(original.id(), restored.id(), "id should be preserved in round-trip");
        assertEquals(original.name(), restored.name(), "name should be preserved in round-trip");
        assertEquals(original.created(), restored.created(), "created should be preserved in round-trip");
        assertEquals(original.modified(), restored.modified(), "modified should be preserved in round-trip");
        assertEquals(
                original.aiResponseCount(),
                restored.aiResponseCount(),
                "aiResponseCount should be preserved in round-trip");
    }

    @Test
    void testSessionInfoDeserializationWithExplicitNull() throws JsonProcessingException {
        // JSON with explicit null for aiResponseCount
        String jsonWithNull =
                """
            {"id":"550e8400-e29b-41d4-a716-446655440000","name":"Null Count","created":1000,"modified":2000,"aiResponseCount":null}
            """;

        SessionInfo info = AbstractProject.objectMapper.readValue(jsonWithNull, SessionInfo.class);

        assertEquals(
                SessionInfo.COUNT_UNKNOWN,
                info.aiResponseCount(),
                "Explicit null aiResponseCount should deserialize to COUNT_UNKNOWN (-1)");
    }

    @Test
    void testSessionInfoDeserializationWithZeroCount() throws JsonProcessingException {
        // JSON with zero count (edge case - valid count value)
        String jsonWithZero =
                """
            {"id":"550e8400-e29b-41d4-a716-446655440000","name":"Zero Count","created":1000,"modified":2000,"aiResponseCount":0}
            """;

        SessionInfo info = AbstractProject.objectMapper.readValue(jsonWithZero, SessionInfo.class);

        assertEquals(0, info.aiResponseCount(), "aiResponseCount of 0 should be preserved (not confused with missing)");
    }
}
