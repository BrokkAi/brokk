package io.github.jbellis.brokk.gui.wand;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.testutil.TestConsoleIO;
import io.github.jbellis.brokk.testutil.TestContextManager;
import java.nio.file.Path;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WandConsoleIOTest {

    @TempDir
    Path tempDir;

    private JTextArea instructionsArea;
    private TestConsoleIO errorReporter;
    private WandConsoleIO wandConsoleIO;
    private TestContextManager contextManager;

    @BeforeEach
    void setUp() throws Exception {
        instructionsArea = new JTextArea();
        errorReporter = new TestConsoleIO();
        wandConsoleIO = new WandConsoleIO(instructionsArea, errorReporter);
        contextManager = new TestContextManager(tempDir, errorReporter);
        awaitEdtIdle();
    }

    @Test
    void testConstructor_initializesDisabledAndText() throws Exception {
        assertFalse(instructionsArea.isEnabled(), "Instructions area should be disabled");
        assertEquals("Improving your prompt...\n\n", instructionsArea.getText(), "Initial banner text should be set");
        assertEquals(
                instructionsArea.getText().length(), instructionsArea.getCaretPosition(), "Caret should be at end");
    }

    @Test
    void testReasoningTokens_appendAndCaretMoves() throws Exception {
        emit("Thinking step 1. ", true, true);
        emit("Thinking step 2.", true, false);

        var expected = "Improving your prompt...\n\nThinking step 1. Thinking step 2.";
        assertEquals(expected, instructionsArea.getText());
        assertEquals(instructionsArea.getText().length(), instructionsArea.getCaretPosition());
    }

    @Test
    void testTransitionFromReasoningToContent_clearsOnce() throws Exception {
        // Start with some reasoning content
        emit("Reasoning...", true, true);

        // Transition to content should clear the area and only show content token(s)
        emit("Final guidance: do X.", false, true);

        assertEquals("Final guidance: do X.", instructionsArea.getText());
        assertEquals(instructionsArea.getText().length(), instructionsArea.getCaretPosition());

        // Additional content should append, not clear again
        emit(" And then do Y.", false, false);
        assertEquals("Final guidance: do X. And then do Y.", instructionsArea.getText());
    }

    @Test
    void testEmptyTokenDuringTransition_clearsButDoesNotAppend() throws Exception {
        emit("Reasoning...", true, true);

        // Transition with empty token clears text but adds nothing
        emit("", false, true);
        assertEquals("", instructionsArea.getText());

        // Subsequent content appends normally
        emit("Start content.", false, false);
        assertEquals("Start content.", instructionsArea.getText());
    }

    @Test
    void testIllegalTransitionBackToReasoning_throws() throws Exception {
        // Enter content mode
        emit("Content begins.", false, true);

        // Attempt to go back to reasoning should throw
        assertThrows(
                IllegalStateException.class,
                () -> wandConsoleIO.llmOutput("Back to chain-of-thought", ChatMessageType.AI, false, true));
    }

    @Test
    void testContentTokens_appendAndCaretMoves() throws Exception {
        // Directly start with content (no reasoning at all)
        emit("Guidance 1.", false, true);
        emit(" Guidance 2.", false, false);

        assertEquals("Guidance 1. Guidance 2.", instructionsArea.getText());
        assertEquals(instructionsArea.getText().length(), instructionsArea.getCaretPosition());
    }

    @Test
    void testToolError_noThrowAndDoesNotAlterText() throws Exception {
        // Capture initial UI text
        var before = instructionsArea.getText();

        // toolError should not throw even if it shows a dialog or logs elsewhere
        assertDoesNotThrow(() -> wandConsoleIO.toolError("Something went wrong", "Oops"));
        awaitEdtIdle();

        // It should not change the instructions area content
        assertEquals(before, instructionsArea.getText(), "toolError should not modify instructions area");
    }

    @Test
    void testGetLlmRawMessages_emptyAndImmutable() {
        var msgs = wandConsoleIO.getLlmRawMessages();
        assertTrue(msgs.isEmpty(), "Expected empty list");
        assertThrows(UnsupportedOperationException.class, () -> msgs.add(null), "List should be immutable");
    }

    @Test
    void testNoInteractionWithContextManager() {
        // Sanity: WandConsoleIO should not affect project context; newly created context is empty.
        assertTrue(contextManager.getFilesInContext().isEmpty(), "No files should have been added to context");
    }

    private static void awaitEdtIdle() throws Exception {
        SwingUtilities.invokeAndWait(() -> {});
    }

    private void emit(String token, boolean isReasoning, boolean isNewMessage) throws Exception {
        wandConsoleIO.llmOutput(token, ChatMessageType.AI, isNewMessage, isReasoning);
        awaitEdtIdle();
    }

    @Test
    void testCancellation_showInterruptDialog_doesNotAlterTextAndHandlesHeadless() throws Exception {
        // Not all implementations may have a cancellation dialog; skip if absent
        java.lang.reflect.Method method;
        try {
            method = WandConsoleIO.class.getDeclaredMethod("showInterruptDialog");
        } catch (NoSuchMethodException e) {
            assumeTrue(false, "WandConsoleIO.showInterruptDialog not present; skipping cancellation test");
            return; // Unreachable, but keeps compiler happy
        }

        var before = instructionsArea.getText();

        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    method.setAccessible(true);
                    // protected method within same package; invoke directly
                    try {
                        method.invoke(wandConsoleIO);
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        // Unwrap and rethrow unless it's headless-related
                        Throwable cause = ite.getCause();
                        if (cause instanceof java.awt.HeadlessException) {
                            // Acceptable under headless CI; proceed to assertions
                            return;
                        }
                        if (cause instanceof RuntimeException re) {
                            throw re;
                        }
                        if (cause instanceof Error err) {
                            throw err;
                        }
                        throw new RuntimeException(cause);
                    }
                } catch (ReflectiveOperationException roe) {
                    throw new RuntimeException(roe);
                }
            });
        } catch (java.awt.HeadlessException he) {
            // Acceptable under headless environment
        }

        awaitEdtIdle();

        // The cancellation dialog should not modify the instructions text content
        assertEquals(before, instructionsArea.getText(), "Cancellation dialog should not modify instructions area");
        // UI should remain disabled as per constructor behavior
        assertFalse(instructionsArea.isEnabled(), "Instructions area should remain disabled after cancellation dialog");
    }
}
