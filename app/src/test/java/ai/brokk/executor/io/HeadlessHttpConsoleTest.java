package ai.brokk.executor.io;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.IConsoleIO;
import ai.brokk.TaskEntry;
import ai.brokk.context.Context;
import ai.brokk.executor.jobs.JobStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageType;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadlessHttpConsoleTest {

    private HeadlessHttpConsole console;
    private JobStore jobStore;
    private String jobId = "test-job-123";

    @BeforeEach
    void setup(@TempDir Path tempDir) throws Exception {
        jobStore = new JobStore(tempDir);
        console = new HeadlessHttpConsole(jobStore, jobId);
    }

    @AfterEach
    void cleanup() {
        console.shutdown(2);
    }

    @Test
    void testLlmOutput_MapsToLlmTokenEvent() throws Exception {
        console.llmOutput("test token", ChatMessageType.AI, true, false);

        // Give event writer thread time to process
        Thread.sleep(100);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(1, events.size());

        var event = events.get(0);
        assertEquals("LLM_TOKEN", event.type());
        assertEquals(1L, event.seq());

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) event.data();
        assertEquals("test token", data.get("token"));
        assertEquals("AI", data.get("messageType"));
        assertEquals(true, data.get("isNewMessage"));
        assertEquals(false, data.get("isReasoning"));

        cleanup();
    }

    @Test
    void testShowNotification_MapsToNotificationEvent() throws Exception {
        console.showNotification(IConsoleIO.NotificationRole.INFO, "Test notification");

        Thread.sleep(100);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(1, events.size());

        var event = events.get(0);
        assertEquals("NOTIFICATION", event.type());

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) event.data();
        assertEquals("INFO", data.get("level"));
        assertEquals("Test notification", data.get("message"));

        cleanup();
    }

    @Test
    void testToolError_MapsToErrorEvent() throws Exception {
        console.toolError("Something went wrong", "Error Title");

        Thread.sleep(100);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(1, events.size());

        var event = events.get(0);
        assertEquals("ERROR", event.type());

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) event.data();
        assertEquals("Something went wrong", data.get("message"));
        assertEquals("Error Title", data.get("title"));

        cleanup();
    }

    @Test
    void testSystemNotify_MapsToNotificationEvent() throws Exception {
        console.systemNotify("System message", "System Title", javax.swing.JOptionPane.INFORMATION_MESSAGE);

        Thread.sleep(100);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(1, events.size());

        var event = events.get(0);
        assertEquals("NOTIFICATION", event.type());

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) event.data();
        assertEquals("INFO", data.get("level"));
        assertEquals("System message", data.get("message"));
        assertEquals("System Title", data.get("title"));

        cleanup();
    }

    @Test
    void testShowConfirmDialog_MapsToConfirmRequestEvent() throws Exception {
        int optionType = javax.swing.JOptionPane.YES_NO_OPTION;
        int messageType = javax.swing.JOptionPane.QUESTION_MESSAGE;
        int decision = console.showConfirmDialog("Proceed?", "Confirm", optionType, messageType);

        // Decision should be the default YES_OPTION for YES_NO* option types
        assertEquals(javax.swing.JOptionPane.YES_OPTION, decision);

        Thread.sleep(100);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(1, events.size());
        var event = events.get(0);
        assertEquals("CONFIRM_REQUEST", event.type());

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) event.data();
        assertEquals("Proceed?", data.get("message"));
        assertEquals("Confirm", data.get("title"));
        assertEquals(optionType, data.get("optionType"));
        assertEquals(messageType, data.get("messageType"));
        assertEquals(javax.swing.JOptionPane.YES_OPTION, data.get("defaultDecision"));

        cleanup();
    }

    @Test
    void testShowConfirmDialog_EmitsConfirmRequestEventAndReturnsDefault() throws Exception {
        int optionType1 = javax.swing.JOptionPane.YES_NO_OPTION;
        int messageType1 = javax.swing.JOptionPane.INFORMATION_MESSAGE;
        int decision1 = console.showConfirmDialog("Proceed with step 1?", "Confirm Step 1", optionType1, messageType1);
        assertEquals(javax.swing.JOptionPane.YES_OPTION, decision1);

        int optionType2 = javax.swing.JOptionPane.OK_CANCEL_OPTION;
        int messageType2 = javax.swing.JOptionPane.WARNING_MESSAGE;
        int decision2 = console.showConfirmDialog(
                new java.awt.Panel(), "Proceed with step 2?", "Confirm Step 2", optionType2, messageType2);
        assertEquals(javax.swing.JOptionPane.OK_OPTION, decision2);

        Thread.sleep(150);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(2, events.size());

        var event1 = events.get(0);
        assertEquals("CONFIRM_REQUEST", event1.type());
        @SuppressWarnings("unchecked")
        var data1 = (Map<String, Object>) event1.data();
        assertEquals("Proceed with step 1?", data1.get("message"));
        assertEquals("Confirm Step 1", data1.get("title"));
        assertEquals(optionType1, data1.get("optionType"));
        assertEquals(messageType1, data1.get("messageType"));
        assertEquals(javax.swing.JOptionPane.YES_OPTION, data1.get("defaultDecision"));

        var event2 = events.get(1);
        assertEquals("CONFIRM_REQUEST", event2.type());
        @SuppressWarnings("unchecked")
        var data2 = (Map<String, Object>) event2.data();
        assertEquals("Proceed with step 2?", data2.get("message"));
        assertEquals("Confirm Step 2", data2.get("title"));
        assertEquals(optionType2, data2.get("optionType"));
        assertEquals(messageType2, data2.get("messageType"));
        assertEquals(javax.swing.JOptionPane.OK_OPTION, data2.get("defaultDecision"));

        cleanup();
    }

    @Test
    void testPrepareOutputForNextStream_MapsToContextBaselineEvent() throws Exception {
        var history = List.of(new TaskEntry(1, null, "task1"), new TaskEntry(2, null, "task2"));

        console.prepareOutputForNextStream(history);

        Thread.sleep(100);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(1, events.size());

        var event = events.get(0);
        assertEquals("CONTEXT_BASELINE", event.type());

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) event.data();
        assertEquals(2, data.get("count"));
        assertTrue(data.get("snippet").toString().contains("tasks=2"));

        cleanup();
    }

    @Test
    void testTranscript_ResetsOnPrepareOutputForNextStream() throws Exception {
        console.llmOutput("Hello", ChatMessageType.AI, true, false);
        console.llmOutput(" world", ChatMessageType.AI, false, false);

        Thread.sleep(100);

        var seqBefore = console.getLastSeq();
        assertTrue(seqBefore >= 0L);
        assertFalse(console.getLlmRawMessages().isEmpty());

        console.prepareOutputForNextStream(List.of());

        Thread.sleep(100);

        var messages = console.getLlmRawMessages();
        assertTrue(messages.isEmpty());

        var events = jobStore.readEvents(jobId, seqBefore, 100);
        assertEquals(1, events.size());

        var event = events.get(0);
        assertEquals("CONTEXT_BASELINE", event.type());
        assertEquals(seqBefore + 1, event.seq());

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) event.data();
        assertEquals(0, data.get("count"));
        assertEquals("empty", data.get("snippet"));

        cleanup();
    }

    @Test
    void testTranscript_ResetsOnSetLlmAndHistoryOutput() throws Exception {
        console.llmOutput("Hello", ChatMessageType.AI, true, false);
        console.llmOutput(" world", ChatMessageType.AI, false, false);

        Thread.sleep(100);

        var seqBefore = console.getLastSeq();
        assertTrue(seqBefore >= 0L);
        assertFalse(console.getLlmRawMessages().isEmpty());

        var history = List.of(new TaskEntry(1, null, "task1"));
        var taskEntry = new TaskEntry(2, null, "task2");

        console.setLlmAndHistoryOutput(history, taskEntry);

        Thread.sleep(100);

        var messages = console.getLlmRawMessages();
        assertTrue(messages.isEmpty());

        var events = jobStore.readEvents(jobId, seqBefore, 100);
        assertEquals(1, events.size());

        var event = events.get(0);
        assertEquals("CONTEXT_BASELINE", event.type());
        assertEquals(seqBefore + 1, event.seq());

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) event.data();
        assertEquals(history.size() + 1, data.get("count"));
        var snippet = data.get("snippet").toString();
        assertFalse(snippet.isEmpty());

        cleanup();
    }

    @Test
    void testSetLlmAndHistoryOutput_MapsToContextBaselineEvent() throws Exception {
        var history = List.of(new TaskEntry(1, null, "task1"), new TaskEntry(2, null, "task2"));
        var taskEntry = new TaskEntry(3, null, "task3");

        console.setLlmAndHistoryOutput(history, taskEntry);

        Thread.sleep(100);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(1, events.size());

        var event = events.get(0);
        assertEquals("CONTEXT_BASELINE", event.type());

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) event.data();
        assertEquals(history.size() + 1, data.get("count"));
        var snippet = data.get("snippet").toString();
        assertFalse(snippet.isEmpty());

        cleanup();
    }

    @Test
    void testBackgroundOutput_MapsToStateHintEvent() throws Exception {
        console.backgroundOutput("Background task description");

        Thread.sleep(100);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(1, events.size());

        var event = events.get(0);
        assertEquals("STATE_HINT", event.type());

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) event.data();
        assertEquals("backgroundTask", data.get("name"));
        assertEquals("Background task description", data.get("value"));

        cleanup();
    }

    @Test
    void testBackgroundOutputWithDetails_MapsToStateHintEvent() throws Exception {
        console.backgroundOutput("Summary", "Detailed info");

        Thread.sleep(100);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(1, events.size());

        var event = events.get(0);
        assertEquals("STATE_HINT", event.type());

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) event.data();
        assertEquals("backgroundTask", data.get("name"));
        assertEquals("Summary", data.get("value"));
        assertEquals("Detailed info", data.get("details"));

        cleanup();
    }

    @Test
    void testSetTaskInProgress_MapsToStateHintEvent() throws Exception {
        console.setTaskInProgress(true);

        Thread.sleep(100);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(1, events.size());

        var event = events.get(0);
        assertEquals("STATE_HINT", event.type());

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) event.data();
        assertEquals("taskInProgress", data.get("name"));
        assertEquals(true, data.get("value"));

        cleanup();
    }

    @Test
    void testUpdateWorkspace_MapsToStateHintEvent() throws Exception {
        console.updateWorkspace();

        Thread.sleep(100);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(1, events.size());

        var event = events.get(0);
        assertEquals("STATE_HINT", event.type());
        assertEquals(1L, event.seq());

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) event.data();
        assertEquals("workspaceUpdated", data.get("name"));
        assertEquals(true, data.get("value"));

        cleanup();
    }

    @Test
    void testUpdateGitRepo_MapsToStateHintEvent() throws Exception {
        console.updateGitRepo();

        Thread.sleep(100);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(1, events.size());

        var event = events.get(0);
        assertEquals("STATE_HINT", event.type());
        assertEquals(1L, event.seq());

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) event.data();
        assertEquals("gitRepoUpdated", data.get("name"));
        assertEquals(true, data.get("value"));

        cleanup();
    }

    @Test
    void testUpdateContextHistoryTable_MapsToStateHintEvent() throws Exception {
        console.updateContextHistoryTable();

        Thread.sleep(100);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(1, events.size());

        var event = events.get(0);
        assertEquals("STATE_HINT", event.type());
        assertEquals(1L, event.seq());

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) event.data();
        assertEquals("contextHistoryUpdated", data.get("name"));
        assertEquals(true, data.get("value"));
        assertEquals(null, data.get("count"));

        cleanup();
    }

    @Test
    void testUpdateContextHistoryTableWithContext_MapsToStateHintEvent() throws Exception {
        console.updateContextHistoryTable(Context.EMPTY);

        Thread.sleep(100);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(1, events.size());

        var event = events.get(0);
        assertEquals("STATE_HINT", event.type());
        assertEquals(1L, event.seq());

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) event.data();
        assertEquals("contextHistoryUpdated", data.get("name"));
        assertEquals(true, data.get("value"));
        assertEquals(1, data.get("count"));

        cleanup();
    }

    @Test
    void testDisableEnableActionButtons_MapsToStateHintEvents() throws Exception {
        console.disableActionButtons();
        console.enableActionButtons();

        Thread.sleep(150);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(2, events.size());

        var event1 = events.get(0);
        assertEquals("STATE_HINT", event1.type());
        @SuppressWarnings("unchecked")
        var data1 = (Map<String, Object>) event1.data();
        assertEquals("actionButtonsEnabled", data1.get("name"));
        assertEquals(false, data1.get("value"));

        var event2 = events.get(1);
        assertEquals("STATE_HINT", event2.type());
        @SuppressWarnings("unchecked")
        var data2 = (Map<String, Object>) event2.data();
        assertEquals("actionButtonsEnabled", data2.get("name"));
        assertEquals(true, data2.get("value"));

        cleanup();
    }

    @Test
    void testShowHideOutputSpinner_MapsToStateHintEvents() throws Exception {
        console.showOutputSpinner("Loading");
        console.hideOutputSpinner();

        Thread.sleep(150);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(2, events.size());

        var event1 = events.get(0);
        assertEquals("STATE_HINT", event1.type());
        @SuppressWarnings("unchecked")
        var data1 = (Map<String, Object>) event1.data();
        assertEquals("outputSpinner", data1.get("name"));
        assertEquals(true, data1.get("value"));

        var event2 = events.get(1);
        assertEquals("STATE_HINT", event2.type());
        @SuppressWarnings("unchecked")
        var data2 = (Map<String, Object>) event2.data();
        assertEquals("outputSpinner", data2.get("name"));
        assertEquals(false, data2.get("value"));

        cleanup();
    }

    @Test
    void testShowHideSessionSwitchSpinner_MapsToStateHintEvents() throws Exception {
        console.showSessionSwitchSpinner();
        console.hideSessionSwitchSpinner();

        Thread.sleep(150);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(2, events.size());

        var event1 = events.get(0);
        assertEquals("STATE_HINT", event1.type());
        @SuppressWarnings("unchecked")
        var data1 = (Map<String, Object>) event1.data();
        assertEquals("sessionSwitchSpinner", data1.get("name"));
        assertEquals(true, data1.get("value"));

        var event2 = events.get(1);
        assertEquals("STATE_HINT", event2.type());
        @SuppressWarnings("unchecked")
        var data2 = (Map<String, Object>) event2.data();
        assertEquals("sessionSwitchSpinner", data2.get("name"));
        assertEquals(false, data2.get("value"));

        cleanup();
    }

    @Test
    void testMultipleEvents_MaintainsOrder() throws Exception {
        console.llmOutput("token1", ChatMessageType.AI, true, false);
        console.toolError("error1", "Error");
        console.showNotification(IConsoleIO.NotificationRole.INFO, "notification1");

        Thread.sleep(150);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(3, events.size());

        assertEquals(1L, events.get(0).seq());
        assertEquals("LLM_TOKEN", events.get(0).type());

        assertEquals(2L, events.get(1).seq());
        assertEquals("ERROR", events.get(1).type());

        assertEquals(3L, events.get(2).seq());
        assertEquals("NOTIFICATION", events.get(2).type());

        cleanup();
    }

    @Test
    void testGetLastSeq_ReturnsLatestSequence() throws Exception {
        console.llmOutput("token1", ChatMessageType.AI, true, false);
        console.llmOutput("token2", ChatMessageType.AI, false, false);

        Thread.sleep(150);

        var lastSeq = console.getLastSeq();
        assertEquals(2L, lastSeq);

        cleanup();
    }

    @Test
    void testEmptyHistory_InContextBaseline() throws Exception {
        console.prepareOutputForNextStream(List.of());

        Thread.sleep(100);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(1, events.size());

        var event = events.get(0);
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) event.data();
        assertEquals(0, data.get("count"));
        assertEquals("empty", data.get("snippet"));

        cleanup();
    }

    @Test
    void testConcurrentEvents_MaintainsOrder() throws Exception {
        // Submit multiple events rapidly
        for (int i = 0; i < 10; i++) {
            console.llmOutput("token" + i, ChatMessageType.AI, i == 0, false);
        }

        // Wait for all events to be written
        Thread.sleep(200);

        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(10, events.size());

        // Verify monotonic sequence numbers
        for (int i = 0; i < events.size(); i++) {
            assertEquals(i + 1L, events.get(i).seq());
            assertEquals("LLM_TOKEN", events.get(i).type());
        }

        cleanup();
    }

    @Test
    void testShutdownWaitsForPendingEvents() throws Exception {
        // Submit events and immediately shutdown
        console.llmOutput("token1", ChatMessageType.AI, true, false);
        console.llmOutput("token2", ChatMessageType.AI, false, false);

        // Shutdown should wait for events to be written
        console.shutdown(5);

        // Verify events were persisted
        var events = jobStore.readEvents(jobId, -1, 100);
        assertEquals(2, events.size());
    }

    @Test
    void testGetLlmRawMessages_RetainsTranscript() {
        console.llmOutput("Hello", ChatMessageType.AI, true, false);
        console.llmOutput(" world", ChatMessageType.AI, false, false);

        var messages = console.getLlmRawMessages();
        assertEquals(1, messages.size());

        var message = messages.getFirst();
        assertEquals(ChatMessageType.AI, message.type());
        var aiMessage = (AiMessage) message;
        assertEquals("Hello world", aiMessage.text());
    }

    @Test
    void testGetLlmRawMessages_ReturnsUnmodifiableSnapshot() {
        console.llmOutput("Hello", ChatMessageType.AI, true, false);
        console.llmOutput(" world", ChatMessageType.AI, false, false);

        var snapshot = console.getLlmRawMessages();

        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(AiMessage.from("oops")));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.remove(0));
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    @Test
    void testGetBlitzForgeListener_ReturnsNoopAndUsesConsole() {
        var cancelRequested = new AtomicBoolean(false);
        Runnable cancelCallback = () -> cancelRequested.set(true);

        var listener = console.getBlitzForgeListener(cancelCallback);

        assertNotNull(listener);
        assertSame(console, listener.getConsoleIO(null));

        assertDoesNotThrow(cancelCallback::run);
        assertTrue(cancelRequested.get());
    }
}
