package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.undo.UndoManager;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * Verifies that assistant-produced prompt overwrites are undoable/redoable as a single step,
 * restoring the user's original input and properly toggling the AI-generated client property.
 *
 * This test constructs an InstructionsPanel instance without calling its normal constructors
 * (to avoid heavyweight Chrome wiring) by allocating an instance and manually initializing the
 * fields required for the minimal interaction exercised below.
 *
 * Note: uses internal Unsafe allocation which is common in tests that need to bypass complex constructors.
 */
public class InstructionsPanelUndoRedoTest {

    @Test
    public void testAssistantOverwriteUndoRedo() throws Exception {
        // Allocate InstructionsPanel instance without invoking constructors
        var unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);

        InstructionsPanel panel = (InstructionsPanel) unsafe.allocateInstance(InstructionsPanel.class);

        // Initialize the minimal fields used by populateInstructionsAreaFromAssistant and undo/redo.
        Field instructionsAreaField = InstructionsPanel.class.getDeclaredField("instructionsArea");
        instructionsAreaField.setAccessible(true);
        JTextArea ta = new JTextArea();
        ta.setEnabled(true);
        instructionsAreaField.set(panel, ta);

        Field undoField = InstructionsPanel.class.getDeclaredField("commandInputUndoManager");
        undoField.setAccessible(true);
        UndoManager undoManager = new UndoManager();
        undoField.set(panel, undoManager);

        // Ensure AI snapshot fields start empty
        Field snapshotField = InstructionsPanel.class.getDeclaredField("aiPromptTrailSnapshot");
        snapshotField.setAccessible(true);
        snapshotField.set(panel, null);

        Field aiFlagField = InstructionsPanel.class.getDeclaredField("aiPromptIsAssistantGenerated");
        aiFlagField.setAccessible(true);
        aiFlagField.set(panel, false);

        // Set initial user input
        SwingUtilities.invokeAndWait(() -> ta.setText("user input"));

        // Invoke the private snapshot recorder (recordAssistantPromptTrailSnapshot) on the EDT so it runs synchronously.
        Method recordSnapshot = InstructionsPanel.class.getDeclaredMethod("recordAssistantPromptTrailSnapshot");
        recordSnapshot.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            try {
                recordSnapshot.invoke(panel);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });

        // Now simulate assistant overwrite via public API and wait for EDT completion.
        SwingUtilities.invokeAndWait(() -> panel.populateInstructionsAreaFromAssistant("refined prompt"));

        // Ensure the instructions area shows the assistant text and is marked AI-generated.
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("refined prompt", ta.getText(), "After assistant overwrite, text should be the refined prompt");
            Object prop = ta.getClientProperty("prompt.aiGenerated");
            assertTrue(Boolean.TRUE.equals(prop), "Client property should mark prompt as AI-generated");
        });

        // Perform undo (on EDT) and wait for pending EDT tasks to complete.
        SwingUtilities.invokeAndWait(() -> {
            undoManager.undo();
        });
        // Flush any nested EDT tasks scheduled by undo's Runnable
        SwingUtilities.invokeAndWait(() -> {});

        // Verify we reverted to the user's original input and AI tag cleared
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("user input", ta.getText(), "After undo, text should revert to original user input");
            assertNull(ta.getClientProperty("prompt.aiGenerated"), "AI client property should be cleared after undo");
        });

        // Perform redo and flush
        SwingUtilities.invokeAndWait(() -> {
            undoManager.redo();
        });
        SwingUtilities.invokeAndWait(() -> {});

        // Verify redo reapplies assistant text and AI tag
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("refined prompt", ta.getText(), "After redo, text should be the refined prompt again");
            Object prop = ta.getClientProperty("prompt.aiGenerated");
            assertTrue(Boolean.TRUE.equals(prop), "Client property should be restored to mark AI-generated content after redo");
        });
    }
}
