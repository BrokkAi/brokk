package io.github.jbellis.brokk.gui.util;

import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;

/**
 * Utility class for creating and registering common keyboard shortcuts.
 * Provides platform-aware shortcut creation and standard registration patterns.
 * 
 * Common shortcuts:
 * - Cmd/Ctrl+F: Focus search field
 * - Cmd/Ctrl+Z: Undo
 * - Cmd/Ctrl+Shift+Z: Redo
 * - Cmd/Ctrl+C: Copy
 * - Cmd/Ctrl+V: Paste
 * - Escape: Clear/Cancel
 */
public class KeyboardShortcutUtil {

    /**
     * Creates a platform-appropriate shortcut using Cmd (Mac) or Ctrl (Windows/Linux).
     */
    public static KeyStroke createPlatformShortcut(int keyCode) {
        return KeyStroke.getKeyStroke(keyCode, 
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
    }

    /**
     * Creates a platform-appropriate shortcut with Shift modifier.
     */
    public static KeyStroke createPlatformShiftShortcut(int keyCode) {
        return KeyStroke.getKeyStroke(keyCode,
            Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK);
    }

    /**
     * Creates a simple shortcut with no modifiers.
     */
    public static KeyStroke createSimpleShortcut(int keyCode) {
        return KeyStroke.getKeyStroke(keyCode, 0);
    }

    /**
     * Registers a global shortcut that works when the component or any of its children have focus.
     */
    public static void registerGlobalShortcut(JComponent component, KeyStroke keyStroke, 
                                            String actionName, Runnable action) {
        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(keyStroke, actionName);
        component.getActionMap().put(actionName, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    /**
     * Registers a shortcut that only works when the component itself has focus.
     */
    public static void registerFocusedShortcut(JComponent component, KeyStroke keyStroke,
                                             String actionName, Runnable action) {
        component.getInputMap(JComponent.WHEN_FOCUSED).put(keyStroke, actionName);
        component.getActionMap().put(actionName, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    /**
     * Registers the standard Ctrl/Cmd+F shortcut to focus a search field.
     * This is the most common search-related shortcut across the application.
     */
    public static void registerSearchFocusShortcut(JComponent component, Runnable focusAction) {
        var ctrlF = createPlatformShortcut(KeyEvent.VK_F);
        registerGlobalShortcut(component, ctrlF, "focusSearch", focusAction);
    }

    /**
     * Registers the standard Escape key to clear search highlights and return focus.
     */
    public static void registerSearchEscapeShortcut(JComponent component, Runnable clearAction) {
        var escape = createSimpleShortcut(KeyEvent.VK_ESCAPE);
        registerFocusedShortcut(component, escape, "clearHighlights", clearAction);
    }

    /**
     * Registers both standard search shortcuts: Ctrl/Cmd+F to focus and Esc to clear.
     * The focus shortcut is registered globally, the escape shortcut on the search field itself.
     */
    public static void registerStandardSearchShortcuts(JComponent parentComponent,
                                                     JComponent searchField,
                                                     Runnable focusAction, 
                                                     Runnable clearAction) {
        registerSearchFocusShortcut(parentComponent, focusAction);
        registerSearchEscapeShortcut(searchField, clearAction);
    }

    /**
     * Common navigation shortcuts for search results.
     */
    public static void registerSearchNavigationShortcuts(JComponent component,
                                                        Runnable nextAction,
                                                        Runnable previousAction) {
        var downArrow = createSimpleShortcut(KeyEvent.VK_DOWN);
        var upArrow = createSimpleShortcut(KeyEvent.VK_UP);
        
        registerFocusedShortcut(component, downArrow, "findNext", nextAction);
        registerFocusedShortcut(component, upArrow, "findPrevious", previousAction);
    }

    // Common shortcut creation methods for frequently used combinations

    /** Creates Cmd/Ctrl+F shortcut */
    public static KeyStroke createCtrlF() {
        return createPlatformShortcut(KeyEvent.VK_F);
    }

    /** Creates Cmd/Ctrl+Z shortcut */
    public static KeyStroke createCtrlZ() {
        return createPlatformShortcut(KeyEvent.VK_Z);
    }

    /** Creates Cmd/Ctrl+Y shortcut */
    public static KeyStroke createCtrlY() {
        return createPlatformShortcut(KeyEvent.VK_Y);
    }

    /** Creates Cmd/Ctrl+Shift+Z shortcut */
    public static KeyStroke createCtrlShiftZ() {
        return createPlatformShiftShortcut(KeyEvent.VK_Z);
    }

    /** Creates Cmd/Ctrl+C shortcut */
    public static KeyStroke createCtrlC() {
        return createPlatformShortcut(KeyEvent.VK_C);
    }

    /** Creates Cmd/Ctrl+V shortcut */
    public static KeyStroke createCtrlV() {
        return createPlatformShortcut(KeyEvent.VK_V);
    }

    /** Creates Cmd/Ctrl+S shortcut */
    public static KeyStroke createCtrlS() {
        return createPlatformShortcut(KeyEvent.VK_S);
    }

    /** Creates Escape key shortcut */
    public static KeyStroke createEscape() {
        return createSimpleShortcut(KeyEvent.VK_ESCAPE);
    }

    /**
     * Registers a dialog escape key that disposes the dialog when pressed.
     * This is a common pattern for modal dialogs.
     */
    public static void registerDialogEscapeKey(JComponent dialogRootPane, Runnable disposeAction) {
        registerGlobalShortcut(dialogRootPane, createEscape(), "dispose", disposeAction);
    }

    /**
     * Registers the standard Ctrl/Cmd+S shortcut for save functionality.
     */
    public static void registerSaveShortcut(JComponent component, Runnable saveAction) {
        registerGlobalShortcut(component, createCtrlS(), "save", saveAction);
    }

    /**
     * Registers the escape key to close/cancel the current context.
     * This is commonly used in panels and dialogs.
     */
    public static void registerCloseEscapeShortcut(JComponent component, Runnable closeAction) {
        registerGlobalShortcut(component, createEscape(), "close", closeAction);
    }
}