package ai.brokk;

import static ai.brokk.SessionManager.SessionInfo;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.concurrent.LoggingFuture;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.ContextHistory;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.util.HistoryIo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SessionManagerTest {
    @TempDir
    Path tempDir;

    private IAppContextManager mockContextManager;

    @BeforeEach
    void setup() throws IOException {
        mockContextManager = new TestContextManager(tempDir, new NoOpConsoleIO());

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

        ContextFragments.StringFragment sf = new ContextFragments.StringFragment(
                mockContextManager, "Test string fragment content", "TestSF", SyntaxConstants.SYNTAX_STYLE_NONE);
        ContextFragments.ProjectPathFragment pf =
                new ContextFragments.ProjectPathFragment(dummyFile, mockContextManager);
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
        // Compare all fragments
        var expectedFragments = expected.allFragments()
                .sorted(Comparator.comparing(ContextFragment::id))
                .toList();
        var actualFragments = actual.allFragments()
                .sorted(Comparator.comparing(ContextFragment::id))
                .toList();
        assertEquals(expectedFragments.size(), actualFragments.size(), "Fragment count mismatch");
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
                expected.referencedFiles().join().stream()
                        .map(ProjectFile::toString)
                        .collect(Collectors.toSet()),
                actual.referencedFiles().join().stream()
                        .map(ProjectFile::toString)
                        .collect(Collectors.toSet()),
                "Fragment files mismatch for ID " + expected.id());
    }

    private void assertTaskEntriesEqual(TaskEntry expected, TaskEntry actual) {
        assertEquals(expected.sequence(), actual.sequence());
        assertEquals(expected.isCompressed(), actual.isCompressed());
        if (expected.isCompressed()) {
            assertEquals(expected.summary(), actual.summary());
        } else {
            assertNotNull(expected.mopLog());
            assertNotNull(actual.mopLog());
            assertEquals(expected.mopLog().description(), actual.mopLog().description());
            assertEquals(expected.mopMarkdown(), actual.mopMarkdown());
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

        boolean success = sessionManager.renameSession(initialSession.id(), "New Name");
        assertTrue(success);

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

        // Test renaming non-existent
        assertFalse(sessionManager.renameSession(UUID.randomUUID(), "Fail"));

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
        assertEventually(() -> assertFalse(Files.exists(historyFileToDelete)));

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
    void testCountAiResponses_missingSession() throws Exception {
        MainProject project = new MainProject(tempDir);
        var sessionManager = project.getSessionManager();
        ;

        // Use a random UUID that doesn't exist
        UUID nonExistentId = SessionManager.newSessionId();

        int count = sessionManager.countAiResponses(nonExistentId);
        assertEquals(0, count, "Non-existent session should return 0");

        project.close();
    }

    @Test
    void testLoadSessionSkippingUnsupportedVersion() throws Exception {
        Path sessionsDir = tempDir.resolve(".brokk").resolve("sessions");
        Files.createDirectories(sessionsDir);
        ObjectMapper mapper = new ObjectMapper();

        // 1. Create a legacy session (version = null)
        UUID legacyId = UUID.randomUUID();
        SessionInfo legacyInfo = new SessionInfo(
                legacyId, "Legacy Session", System.currentTimeMillis(), System.currentTimeMillis(), null);
        createSessionZip(sessionsDir, legacyInfo, mapper);

        // 2. Create a future session (version = "99.0")
        UUID futureId = UUID.randomUUID();
        SessionInfo futureInfo = new SessionInfo(
                futureId, "Future Session", System.currentTimeMillis(), System.currentTimeMillis(), "99.0");
        createSessionZip(sessionsDir, futureInfo, mapper);

        // 3. Create a supported session (version = current)
        UUID currentId = UUID.randomUUID();
        SessionInfo currentInfo =
                new SessionInfo(currentId, "Current Session", System.currentTimeMillis(), System.currentTimeMillis());
        createSessionZip(sessionsDir, currentInfo, mapper);

        // Initialize SessionManager (loads sessions from disk)
        MainProject project = new MainProject(tempDir);
        var sessionManager = project.getSessionManager();
        List<SessionInfo> sessions = sessionManager.listSessions();

        Set<UUID> loadedIds = sessions.stream().map(SessionInfo::id).collect(Collectors.toSet());

        assertTrue(loadedIds.contains(legacyId), "Should load legacy session (null version)");
        assertTrue(loadedIds.contains(currentId), "Should load current version session");
        assertFalse(loadedIds.contains(futureId), "Should NOT load future version session");

        // Verify file existence
        assertTrue(
                Files.exists(sessionsDir.resolve(futureId.toString() + ".zip")),
                "Future session zip should still exist on disk");

        project.close();
    }

    @Test
    void testCompareVersions() {
        // Equal versions
        assertEquals(0, SessionManager.compareVersions("1.0", "1.0"));
        assertEquals(0, SessionManager.compareVersions("2.3.4", "2.3.4"));

        // Ordering
        assertEquals(-1, SessionManager.compareVersions("1.0", "1.1"));
        assertEquals(1, SessionManager.compareVersions("1.1", "1.0"));

        assertEquals(-1, SessionManager.compareVersions("1.9", "1.10"));
        assertEquals(1, SessionManager.compareVersions("1.10", "1.9"));

        assertEquals(-1, SessionManager.compareVersions("1.0", "2.0"));
        assertEquals(1, SessionManager.compareVersions("2.0", "1.0"));

        // Different number of components
        assertEquals(0, SessionManager.compareVersions("1.0", "1.0.0"));
        assertEquals(0, SessionManager.compareVersions("1.0.0", "1.0"));

        assertEquals(-1, SessionManager.compareVersions("1.0", "1.0.1"));
        assertEquals(1, SessionManager.compareVersions("1.0.1", "1.0"));

        // Single component versions
        assertEquals(0, SessionManager.compareVersions("4", "4.0"));
        assertEquals(0, SessionManager.compareVersions("4.0", "4"));
        assertEquals(-1, SessionManager.compareVersions("4", "4.1"));
        assertEquals(1, SessionManager.compareVersions("4.1", "4"));
    }

    @Test
    void testQuarantineUnreadableSessions_RemovesCachedSessionWhenManifestUnreadable() throws Exception {
        MainProject project = new MainProject(tempDir);
        SessionManager sessionManager = project.getSessionManager();
        Path sessionsDir = sessionManager.getSessionsDir();

        UUID sessionId = UUID.randomUUID();
        SessionInfo info = new SessionInfo(
                sessionId, "Manifest Broken Session", System.currentTimeMillis(), System.currentTimeMillis());
        createSessionZip(sessionsDir, info, new ObjectMapper());

        // Simulate a previously loaded session that is now corrupted on disk.
        sessionManager.getSessionsCache().put(sessionId, info);
        Files.write(sessionsDir.resolve(sessionId + ".zip"), "not-a-zip-at-all".getBytes());

        SessionManager.QuarantineReport report = sessionManager.quarantineUnreadableSessions(mockContextManager);

        assertTrue(report.quarantinedSessionIds().contains(sessionId), "Session should be in report quarantinedIds");
        assertFalse(sessionManager.getSessionsCache().containsKey(sessionId), "Session should be removed from cache");

        Path unreadableZip =
                sessionsDir.resolve(SessionManager.UNREADABLE_SESSIONS_DIR).resolve(sessionId + ".zip");
        assertTrue(Files.exists(unreadableZip), "Session zip should have been moved to unreadable directory");

        project.close();
    }

    @Test
    void testQuarantineUnreadableSessions_ExecutionExceptionDuringValidation() throws Exception {
        MainProject project = new MainProject(tempDir);
        SessionManager sessionManager = project.getSessionManager();
        Path sessionsDir = sessionManager.getSessionsDir();

        // 1. Create a session that looks valid (UUID filename + manifest)
        UUID sessionId = UUID.randomUUID();
        SessionInfo info =
                new SessionInfo(sessionId, "Broken Session", System.currentTimeMillis(), System.currentTimeMillis());
        createSessionZip(sessionsDir, info, new ObjectMapper());

        // 2. Corrupt the zip in a way that causes HistoryIo.readZip (called via loadHistoryOrQuarantine) to fail.
        // We do this by writing invalid bytes over the zip file.
        Files.write(sessionsDir.resolve(sessionId + ".zip"), "not-a-zip-at-all".getBytes());

        // 3. Run quarantine scan
        SessionManager.QuarantineReport report = sessionManager.quarantineUnreadableSessions(mockContextManager);

        // 4. Assertions
        assertTrue(report.quarantinedSessionIds().contains(sessionId), "Session should be in report quarantinedIds");
        assertEquals(1, report.movedCount(), "Moved count should be 1");

        // Verify it was moved to unreadable dir
        Path unreadableZip =
                sessionsDir.resolve(SessionManager.UNREADABLE_SESSIONS_DIR).resolve(sessionId + ".zip");
        assertTrue(Files.exists(unreadableZip), "Session zip should have been moved to unreadable directory");

        // Verify removed from cache
        assertFalse(sessionManager.getSessionsCache().containsKey(sessionId), "Session should be removed from cache");

        project.close();
    }

    private void createSessionZip(Path sessionsDir, SessionInfo info, ObjectMapper mapper) throws IOException {
        Path zipPath = sessionsDir.resolve(info.id() + ".zip");
        // Create new zip file
        try (var fs = FileSystems.newFileSystem(zipPath, Map.of("create", "true"))) {
            Path manifestPath = fs.getPath("manifest.json");
            String json = mapper.writeValueAsString(info);
            Files.writeString(manifestPath, json);
        }
    }

    @Test
    void testQuarantineDoesNotRaceWithInFlightWrites() throws Exception {
        MainProject project = new MainProject(tempDir);
        var sessionManager = project.getSessionManager();
        UUID sessionId = SessionManager.newSessionId();
        Path zipPath = sessionManager.getSessionHistoryPath(sessionId);

        // 1. Setup: Create a "partial" zip (zip exists but no manifest yet)
        Files.createDirectories(zipPath.getParent());
        var emptyHistory = new ContextHistory(new Context(mockContextManager));
        HistoryIo.writeZip(emptyHistory, zipPath);

        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch quarantineCanProceed = new CountDownLatch(1);

        // 2. Queue a delayed manifest write on the session executor
        var sessionInfo =
                new SessionInfo(sessionId, "Race Test", System.currentTimeMillis(), System.currentTimeMillis());
        sessionManager.getSessionExecutorByKey().submit(sessionId.toString(), () -> {
            writerStarted.countDown();
            try {
                quarantineCanProceed.await();
                sessionManager.writeSessionInfoToZip(zipPath, sessionInfo);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });

        writerStarted.await();

        // 3. Invoke quarantine concurrently.
        // On old code, this would see the zip, try to read manifest, fail, and quarantine it.
        // On new code, this submits to the same key and waits, so it runs AFTER the manifest write.
        var quarantineTask = LoggingFuture.supplyCallableAsync(() -> {
            return sessionManager.quarantineUnreadableSessions(mockContextManager);
        });

        // Small sleep to ensure quarantine task is likely blocked on the executor key
        Thread.sleep(100);
        quarantineCanProceed.countDown();

        var report = quarantineTask.get();

        // 4. Assertions
        assertFalse(
                report.quarantinedSessionIds().contains(sessionId),
                "Session should not have been quarantined because checks are serialized");
        assertTrue(Files.exists(zipPath), "Session zip should still exist at original path");

        var sessions = sessionManager.listSessions();
        assertTrue(
                sessions.stream().anyMatch(s -> s.id().equals(sessionId)),
                "Session should be present in listed sessions");
    }

    @Test
    void testDeriveSessionName() {
        assertEquals("Simple prompt", SessionManager.deriveSessionName("Simple prompt"));
        assertEquals("fix this", SessionManager.deriveSessionName("@lutz fix this"));
        assertEquals("how does this work?", SessionManager.deriveSessionName("/ask how does this work?"));
        assertEquals("multi-line", SessionManager.deriveSessionName("multi-line\nsecond line"));
        assertEquals("multi-line", SessionManager.deriveSessionName("/code multi-line\nsecond line"));

        String longPrompt = "A".repeat(100);
        String derived = SessionManager.deriveSessionName(longPrompt);
        assertEquals(60, derived.length());
        assertTrue(derived.endsWith("..."));

        // Combined prefixes
        assertEquals("do it", SessionManager.deriveSessionName("@bot /ask /code do it"));

        // Case insensitivity and whitespace
        assertEquals("case test", SessionManager.deriveSessionName("/ASK  case test"));
        assertEquals("mixed test", SessionManager.deriveSessionName("@Brokk /Lutz mixed test"));
    }

    @Test
    void testAutoRenameIfDefault() throws Exception {
        MainProject project = new MainProject(tempDir);
        var sm = project.getSessionManager();

        // 1. Test valid rename from default
        SessionInfo session1 = sm.newSession("New Session");
        sm.autoRenameIfDefault(session1.id(), "My special task input").get();
        assertEquals(
                "My special task input",
                sm.getSessionsCache().get(session1.id()).name());

        // 2. Test non-default name remains unchanged
        SessionInfo session2 = sm.newSession("Custom Name");
        sm.autoRenameIfDefault(session2.id(), "Should not change").get();
        assertEquals("Custom Name", sm.getSessionsCache().get(session2.id()).name());

        // 3. Test blank/invalid derived name remains unchanged
        SessionInfo session3 = sm.newSession("Session");
        sm.autoRenameIfDefault(session3.id(), "   ").get();
        assertEquals("Session", sm.getSessionsCache().get(session3.id()).name());
        project.close();
    }
}
