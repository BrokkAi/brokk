package ai.brokk.gui.components;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.event.ContainerListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility to apply conservative uniform width sizing to MaterialButton instances that appear inside dialogs.
 *
 * <p>Why separate from MaterialButton:
 * - We want dialog-only behavior without changing MaterialButton's global styling.
 * - By keeping logic here we can attach/remove listeners dynamically and avoid impacting non-dialog usages.
 *
 * <p>Behavior summary:
 * - Only operates when a button's Window ancestor is a {@link JDialog}.
 * - Scopes sizing to sibling MaterialButton instances that share the same immediate parent container.
 * - Only modifies the preferredSize.width of affected buttons; preferredSize.height is preserved from the stored original.
 * - Uses client properties on each button:
 *     "dialogSizingApplied" -> Boolean.TRUE when sizing has been applied
 *     "dialogSizingOriginalPreferred" -> Dimension holding the original preferred size to allow restoration
 * - Attaches ContainerListeners to parent containers and a WindowListener to the dialog to recompute sizing and to
 *   clean up state when the dialog closes.
 *
 * <p>All public methods are safe to call from any thread; they schedule work on the EDT as needed.
 */
public final class DialogButtonSizing {
    private static final Logger logger = LogManager.getLogger(DialogButtonSizing.class);

    private static final String APPLIED_PROP = "dialogSizingApplied";
    private static final String ORIGINAL_PREF_PROP = "dialogSizingOriginalPreferred";

    // Track listeners with weak keys so GC can collect containers/windows no longer referenced elsewhere.
    private static final Map<Container, ContainerListener> parentListeners =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Window, WindowListener> dialogListeners = Collections.synchronizedMap(new WeakHashMap<>());

    private DialogButtonSizing() {
        // Utility class - no instances
    }

    /**
     * Register the given MaterialButton for dialog-scoped sizing if it is contained within a JDialog.
     * This method is safe to call from any thread.
     *
     * @param b the MaterialButton that may need dialog sizing
     */
    public static void registerIfInDialog(MaterialButton b) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> registerIfInDialog(b));
            return;
        }

        Container parent = b.getParent();
        if (parent == null) {
            // Not yet in a container; nothing to do now.
            return;
        }

        Window w = SwingUtilities.getWindowAncestor(b);
        if (!(w instanceof JDialog)) {
            // Only apply sizing for dialogs (including JOptionPane-created dialogs)
            return;
        }

        // Attach a container listener to the immediate parent to recompute when children are added/removed.
        parentListeners.computeIfAbsent(parent, p -> {
            ContainerListener cl = new ContainerAdapter() {
                @Override
                public void componentAdded(ContainerEvent e) {
                    recomputeForContainer(p);
                }

                @Override
                public void componentRemoved(ContainerEvent e) {
                    recomputeForContainer(p);
                }
            };
            p.addContainerListener(cl);
            return cl;
        });

        // Attach a window listener to the dialog to perform cleanup when the dialog is closed/disposed.
        dialogListeners.computeIfAbsent(w, win -> {
            WindowListener wl = new WindowAdapter() {
                @Override
                public void windowOpened(WindowEvent e) {
                    // Ensure sizes are correct once the window opens.
                    recomputeForWindow(e.getWindow());
                }

                @Override
                public void windowActivated(WindowEvent e) {
                    // Recompute on activation to handle any dynamic changes prior to visible display.
                    recomputeForWindow(e.getWindow());
                }

                @Override
                public void windowClosed(WindowEvent e) {
                    cleanupWindow(e.getWindow());
                }
            };
            win.addWindowListener(wl);
            return wl;
        });

        // Apply sizing immediately for this parent (conservative: only immediate siblings)
        recomputeForContainer(parent);
    }

    /**
     * Unregister a MaterialButton and restore its original preferred size if sizing was applied.
     * Also triggers recomputation for the parent container so remaining siblings update accordingly.
     *
     * @param b the MaterialButton to unregister
     */
    public static void unregister(MaterialButton b) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> unregister(b));
            return;
        }

        // Restore this button if we previously altered it
        restoreOriginalIfPresent(b);

        Container parent = b.getParent();
        if (parent != null) {
            // Recompute the remaining siblings (if any)
            recomputeForContainer(parent);

            // If parent has no more MaterialButton children, remove the container listener to avoid leaks
            boolean hasAny = false;
            for (Component c : parent.getComponents()) {
                if (c instanceof MaterialButton) {
                    hasAny = true;
                    break;
                }
            }
            if (!hasAny) {
                ContainerListener cl = parentListeners.remove(parent);
                if (cl != null) {
                    try {
                        parent.removeContainerListener(cl);
                    } catch (Exception e) {
                        logger.debug("Failed to remove container listener for parent {}", parent, e);
                    }
                }
            }
        }
    }

    // Recompute sizing for MaterialButton children directly inside the provided parent container.
    private static void recomputeForContainer(Container parent) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> recomputeForContainer(parent));
            return;
        }

        // Collect visible/displayable MaterialButton children in immediate parent
        List<MaterialButton> buttons = new ArrayList<>();
        for (Component c : parent.getComponents()) {
            if (c instanceof MaterialButton mb) {
                buttons.add(mb);
            }
        }

        if (buttons.size() <= 1) {
            // Nothing to unify; restore originals for any that were modified
            for (MaterialButton mb : buttons) {
                restoreOriginalIfPresent(mb);
            }
            return;
        }

        // Compute max width using stored original preferred width if available, otherwise current preferred width.
        int maxWidth = 0;
        for (MaterialButton mb : buttons) {
            Object stored = mb.getClientProperty(ORIGINAL_PREF_PROP);
            int w;
            if (stored instanceof Dimension d) {
                w = d.width;
            } else {
                Dimension cur = mb.getPreferredSize();
                w = cur != null ? cur.width : 0;
            }
            if (w > maxWidth) maxWidth = w;
        }

        // Apply uniform width to all buttons, preserving original height (store original if not stored yet).
        for (MaterialButton mb : buttons) {
            Object applied = mb.getClientProperty(APPLIED_PROP);
            if (!(applied instanceof Boolean b && b)) {
                // store original preferred size
                Dimension cur = mb.getPreferredSize();
                if (cur != null) {
                    // make a defensive copy
                    mb.putClientProperty(ORIGINAL_PREF_PROP, new Dimension(cur.width, cur.height));
                }
            }
            // Determine height to preserve
            Dimension original = (Dimension) mb.getClientProperty(ORIGINAL_PREF_PROP);
            int height = original != null
                    ? original.height
                    : (mb.getPreferredSize() != null ? mb.getPreferredSize().height : -1);
            if (height < 0) {
                // Fallback height if none available
                height = mb.getHeight() > 0 ? mb.getHeight() : mb.getPreferredSize().height;
            }
            mb.setPreferredSize(new Dimension(maxWidth, height));
            mb.putClientProperty(APPLIED_PROP, true);
            mb.revalidate();
            mb.repaint();
        }
    }

    // Restore original preferred size stored in client property, if present.
    private static void restoreOriginalIfPresent(MaterialButton mb) {
        Object orig = mb.getClientProperty(ORIGINAL_PREF_PROP);
        if (orig instanceof Dimension d) {
            try {
                mb.setPreferredSize(new Dimension(d.width, d.height));
            } catch (Exception e) {
                logger.debug("Failed to restore original preferred size for {}", mb, e);
            }
        }
        // Clear client properties
        mb.putClientProperty(APPLIED_PROP, null);
        mb.putClientProperty(ORIGINAL_PREF_PROP, null);
        mb.revalidate();
        mb.repaint();
    }

    // Recompute for all parent containers owned by a given window (dialog)
    private static void recomputeForWindow(Window w) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> recomputeForWindow(w));
            return;
        }

        // Walk the component tree starting at the window's content pane and recompute for each immediate container
        Container root = (w instanceof JDialog jd) ? jd.getContentPane() : w;
        if (root == null) return;

        // Use a simple DFS stack to visit containers
        var stack = new ArrayDeque<Container>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Container c = stack.pop();
            // Recompute for this container if we have MaterialButton children
            boolean hasButton = false;
            for (Component comp : c.getComponents()) {
                if (comp instanceof MaterialButton) {
                    hasButton = true;
                    break;
                }
            }
            if (hasButton) {
                recomputeForContainer(c);
            }
            // push child containers
            for (Component comp : c.getComponents()) {
                if (comp instanceof Container child) {
                    stack.push(child);
                }
            }
        }
    }

    // Cleanup: restore all buttons under the window and remove listeners for containers that belong to the window.
    private static void cleanupWindow(Window w) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> cleanupWindow(w));
            return;
        }

        // Restore any MaterialButton within the window hierarchy
        Container root = (w instanceof JDialog jd) ? jd.getContentPane() : w;
        if (root != null) {
            // traverse all components and restore MaterialButtons
            var stack = new ArrayDeque<Container>();
            stack.push(root);
            while (!stack.isEmpty()) {
                Container c = stack.pop();
                for (Component comp : c.getComponents()) {
                    if (comp instanceof MaterialButton mb) {
                        restoreOriginalIfPresent(mb);
                    } else if (comp instanceof Container child) {
                        stack.push(child);
                    }
                }
            }
        }

        // Remove & detach any container listeners whose container is part of this window
        List<Container> toRemove = new ArrayList<>();
        synchronized (parentListeners) {
            for (Container cont : parentListeners.keySet()) {
                if (isAncestor(w, cont)) {
                    ContainerListener cl = parentListeners.get(cont);
                    if (cl != null) {
                        try {
                            cont.removeContainerListener(cl);
                        } catch (Exception e) {
                            logger.debug("Failed to remove container listener during window cleanup for {}", cont, e);
                        }
                    }
                    toRemove.add(cont);
                }
            }
            for (Container cont : toRemove) parentListeners.remove(cont);
        }

        // Remove and detach the window listener itself
        WindowListener wl = dialogListeners.remove(w);
        if (wl != null) {
            try {
                w.removeWindowListener(wl);
            } catch (Exception e) {
                logger.debug("Failed to remove window listener during window cleanup for {}", w, e);
            }
        }
    }

    // Utility to check whether 'ancestor' is an ancestor of 'child' (walk up parents)
    private static boolean isAncestor(Window ancestor, Container child) {
        Container c = child;
        while (c != null) {
            if (c == ancestor) return true;
            java.awt.Component parent = c.getParent();
            if (parent instanceof Container pc) c = pc;
            else break;
        }
        return false;
    }
}
