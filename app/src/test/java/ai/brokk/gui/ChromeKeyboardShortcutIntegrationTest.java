package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import ai.brokk.gui.util.KeyboardShortcutUtil;
import ai.brokk.util.GlobalUiSettings;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import javax.swing.undo.UndoManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ChromeKeyboardShortcutIntegrationTest {

    @BeforeEach
    void resetSettings() {
        GlobalUiSettings.resetForTests();
    }

    private static boolean isMacOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac");
    }

    @Test
    void redoKeyStrokesBindToGlobalRedoAction_NonMac() {
        assumeFalse(isMacOs(), "Redo keybinding test is specific to non-macOS behavior");

        JRootPane rootPane = new JRootPane();

        AtomicInteger redoInvocations = new AtomicInteger();
        Action globalRedoAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                redoInvocations.incrementAndGet();
            }
        };

        Action globalUndoAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // no-op
            }
        };

        Chrome.registerUndoRedoShortcuts(rootPane, globalUndoAction, globalRedoAction);

        // Use the effective configured redo keystrokes here. Chrome.registerUndoRedoShortcuts
        // consults GlobalUiSettings for these IDs with these fallbacks, so these are the
        // exact KeyStrokes that should be wired to the globalRedo action key.
        KeyStroke redoKeyStroke =
                GlobalUiSettings.getKeybinding("global.redo", KeyboardShortcutUtil.createCtrlShiftZ());
        KeyStroke redoYKeyStroke = GlobalUiSettings.getKeybinding("global.redoY", KeyboardShortcutUtil.createCtrlY());

        InputMap inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = rootPane.getActionMap();

        Object actionKeyZ = inputMap.get(redoKeyStroke);
        Object actionKeyY = inputMap.get(redoYKeyStroke);

        assertEquals("globalRedo", actionKeyZ);
        assertEquals("globalRedo", actionKeyY);

        Action mappedAction = actionMap.get("globalRedo");
        assertNotNull(mappedAction);
        assertSame(globalRedoAction, mappedAction);

        mappedAction.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "redoZ"));
        mappedAction.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "redoY"));

        assertEquals(2, redoInvocations.get());
    }

    @Test
    void performGlobalRedo_InvokesContextRedoWhenFocusInContextAndRedoAvailable() {
        JTextArea instructionsArea = new JTextArea();
        UndoManager undoManager = new UndoManager();

        JPanel contextRoot = new JPanel();
        JButton contextChild = new JButton("ctx");
        contextRoot.add(contextChild);
        JTable historyTable = new JTable();
        contextRoot.add(historyTable);

        AtomicInteger contextRedoInvocations = new AtomicInteger();

        Chrome.performGlobalRedo(
                contextChild,
                instructionsArea,
                undoManager,
                contextRoot,
                historyTable,
                true,
                contextRedoInvocations::incrementAndGet);

        assertEquals(1, contextRedoInvocations.get());
    }

    @Test
    void performGlobalRedo_DoesNotInvokeContextRedoWhenFocusOutsideContext() {
        JTextArea instructionsArea = new JTextArea();
        UndoManager undoManager = new UndoManager();

        JPanel contextRoot = new JPanel();
        JButton contextChild = new JButton("ctx");
        contextRoot.add(contextChild);
        JTable historyTable = new JTable();
        contextRoot.add(historyTable);

        AtomicInteger contextRedoInvocations = new AtomicInteger();

        Component unrelatedFocusOwner = new JButton("other");

        Chrome.performGlobalRedo(
                unrelatedFocusOwner,
                instructionsArea,
                undoManager,
                contextRoot,
                historyTable,
                true,
                contextRedoInvocations::incrementAndGet);

        assertEquals(0, contextRedoInvocations.get());
    }

    @Test
    void performGlobalRedo_DoesNotInvokeContextRedoWhenFocusedInInstructions() {
        JTextArea instructionsArea = new JTextArea();
        UndoManager undoManager = new UndoManager();

        JPanel contextRoot = new JPanel();
        JTable historyTable = new JTable();
        contextRoot.add(historyTable);

        AtomicInteger contextRedoInvocations = new AtomicInteger();

        Chrome.performGlobalRedo(
                instructionsArea,
                instructionsArea,
                undoManager,
                contextRoot,
                historyTable,
                true,
                contextRedoInvocations::incrementAndGet);

        assertEquals(0, contextRedoInvocations.get());
    }
}
