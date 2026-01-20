package ai.brokk.gui.util;

import com.formdev.flatlaf.util.SystemInfo;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.KeyStroke;

public class KeyboardShortcutUtil {

    public static KeyStroke createPlatformShortcut(int keyCode) {
        int modifier = SystemInfo.isMacOS ? KeyEvent.META_DOWN_MASK : KeyEvent.CTRL_DOWN_MASK;
        return KeyStroke.getKeyStroke(keyCode, modifier);
    }

    public static KeyStroke createPlatformShiftShortcut(int keyCode) {
        int modifier = (SystemInfo.isMacOS ? KeyEvent.META_DOWN_MASK : KeyEvent.CTRL_DOWN_MASK) | InputEvent.SHIFT_DOWN_MASK;
        return KeyStroke.getKeyStroke(keyCode, modifier);
    }

    public static KeyStroke createAltShortcut(int keyCode) {
        int modifier = SystemInfo.isMacOS ? KeyEvent.META_DOWN_MASK : KeyEvent.ALT_DOWN_MASK;
        return KeyStroke.getKeyStroke(keyCode, modifier);
    }

    public static KeyStroke createAltShiftShortcut(int keyCode) {
        int modifier = (SystemInfo.isMacOS ? KeyEvent.META_DOWN_MASK : KeyEvent.ALT_DOWN_MASK) | InputEvent.SHIFT_DOWN_MASK;
        return KeyStroke.getKeyStroke(keyCode, modifier);
    }

    public static KeyStroke createSimpleShortcut(int keyCode) {
        return KeyStroke.getKeyStroke(keyCode, 0);
    }

    /** Registers a global shortcut that works when the component or any of its children have focus. */
    public static void registerGlobalShortcut(
            JComponent component, KeyStroke keyStroke, String actionName, Runnable action) {
        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(keyStroke, actionName);
        component.getActionMap().put(actionName, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    /** Registers a shortcut that only works when the component itself has focus. */
    public static void registerFocusedShortcut(
            JComponent component, KeyStroke keyStroke, String actionName, Runnable action) {
        var im = component.getInputMap(JComponent.WHEN_FOCUSED);
        removeAllKeyStrokesMappedToAction(im, actionName);
        im.put(keyStroke, actionName);

        component.getActionMap().put(actionName, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    public static void removeAllKeyStrokesMappedToAction(InputMap inputMap, String actionName) {
        for (InputMap im = inputMap; im != null; im = im.getParent()) {
            KeyStroke[] keys = im.keys();
            if (keys == null || keys.length == 0) continue;

            for (KeyStroke ks : keys) {
                Object val = im.get(ks);
                if (actionName.equals(val)) {
                    im.remove(ks);
                }
            }
        }
    }

    public static void registerSearchFocusShortcut(JComponent component, Runnable focusAction) {
        registerGlobalShortcut(component, createCtrlF(), "searchFocus", focusAction);
    }

    public static void registerSearchEscapeShortcut(JComponent component, Runnable clearAction) {
        registerGlobalShortcut(component, createEscape(), "searchEscape", clearAction);
    }

    public static void registerStandardSearchShortcuts(
            JComponent parentComponent, JComponent searchField, Runnable focusAction, Runnable clearAction) {
        registerSearchFocusShortcut(parentComponent, focusAction);
        registerSearchEscapeShortcut(searchField, clearAction);
    }

    public static void registerSearchNavigationShortcuts(
            JComponent component, Runnable nextAction, Runnable previousAction) {
        registerGlobalShortcut(component, createPlatformShortcut(KeyEvent.VK_G), "searchNext", nextAction);
        registerGlobalShortcut(component, createPlatformShiftShortcut(KeyEvent.VK_G), "searchPrevious", previousAction);
    }

    public static KeyStroke createCtrlF() {
        return createPlatformShortcut(KeyEvent.VK_F);
    }

    public static KeyStroke createCtrlZ() {
        return createPlatformShortcut(KeyEvent.VK_Z);
    }

    public static KeyStroke createCtrlY() {
        return createPlatformShortcut(KeyEvent.VK_Y);
    }

    public static KeyStroke createCtrlShiftZ() {
        return createPlatformShiftShortcut(KeyEvent.VK_Z);
    }

    public static KeyStroke createCtrlC() {
        return createPlatformShortcut(KeyEvent.VK_C);
    }

    public static KeyStroke createCtrlV() {
        return createPlatformShortcut(KeyEvent.VK_V);
    }

    public static KeyStroke createCtrlS() {
        return createPlatformShortcut(KeyEvent.VK_S);
    }

    public static KeyStroke createEscape() {
        return createSimpleShortcut(KeyEvent.VK_ESCAPE);
    }

    public static void registerDialogEscapeKey(JComponent dialogRootPane, Runnable disposeAction) {
        registerGlobalShortcut(dialogRootPane, createEscape(), "dialogEscape", disposeAction);
    }

    /** Registers the standard Ctrl/Cmd+S shortcut for save functionality. */
    public static void registerSaveShortcut(JComponent component, Runnable saveAction) {
        registerGlobalShortcut(component, createCtrlS(), "save", saveAction);
    }

    /** Registers the escape key to close/cancel the current context. This is commonly used in panels and dialogs. */
    public static void registerCloseEscapeShortcut(JComponent component, Runnable closeAction) {
        registerGlobalShortcut(component, createEscape(), "close", closeAction);
    }

    public static String formatKeyStroke(KeyStroke ks) {
        try {
            int modifiers = ks.getModifiers();
            int keyCode = ks.getKeyCode();
            String modText = InputEvent.getModifiersExText(modifiers);
            String keyText = KeyEvent.getKeyText(keyCode);
            if (modText == null || modText.isBlank()) return keyText;
            return modText + "+" + keyText;
        } catch (Exception e) {
            return ks.toString();
        }
    }
}
