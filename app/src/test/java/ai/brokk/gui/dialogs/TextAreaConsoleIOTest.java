package ai.brokk.gui.dialogs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.brokk.IConsoleIO;
import dev.langchain4j.data.message.ChatMessageType;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TextAreaConsoleIOTest {

    private JTextArea textArea;
    private TextAreaConsoleIO consoleIO;

    @BeforeEach
    void setUp() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            textArea = new JTextArea();
            consoleIO = new TextAreaConsoleIO(textArea, null, "Thinking");
        });
    }

    @Test
    void testNormalReasoningToContentFlow() throws Exception {
        // 1. Reasoning tokens
        consoleIO.llmOutput("Thought 1", ChatMessageType.AI, false, true);
        consoleIO.llmOutput("Thought 2", ChatMessageType.AI, false, true);

        // 2. Transition to content
        consoleIO.llmOutput("Hello", ChatMessageType.AI, false, false);
        consoleIO.llmOutput(" world", ChatMessageType.AI, false, false);

        SwingUtilities.invokeAndWait(() -> {
            assertEquals("Hello world", textArea.getText());
        });
    }

    @Test
    void testSpuriousNonReasoningTokenBeforeReasoning() throws Exception {
        // 1. Spurious empty non-reasoning token
        consoleIO.llmOutput("", ChatMessageType.AI, false, false);

        // 2. Actual reasoning tokens follow - should NOT throw anymore
        consoleIO.llmOutput("Thinking...", ChatMessageType.AI, false, true);
        consoleIO.llmOutput(" still thinking", ChatMessageType.AI, false, true);

        // 3. Then real content starts
        consoleIO.llmOutput("Real content", ChatMessageType.AI, false, false);

        SwingUtilities.invokeAndWait(() -> {
            assertEquals("Real content", textArea.getText());
        });
    }

    @Test
    void testSwitchBackToReasoningAfterContentThrows() {
        // 1. Reasoning
        consoleIO.llmOutput("Thinking", ChatMessageType.AI, false, true);

        // 2. Real content starts
        consoleIO.llmOutput("Content", ChatMessageType.AI, false, false);

        // 3. Attempting to switch back to reasoning should throw
        assertThrows(IllegalStateException.class, () -> {
            consoleIO.llmOutput("More thinking?", ChatMessageType.AI, false, true);
        });
    }
}
