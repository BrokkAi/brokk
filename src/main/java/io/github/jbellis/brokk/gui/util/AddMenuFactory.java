package io.github.jbellis.brokk.gui.util;

import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.WorkspacePanel;

import javax.swing.*;
import java.util.List;

public final class AddMenuFactory {
    private AddMenuFactory() {}

    /** Builds the Add popup that contains Edit, Read, Summarize, Symbol Usage */
    public static JPopupMenu buildAddPopup(WorkspacePanel wp) {
        var popup = new JPopupMenu();
        // For the @-triggered menu, include call graph items
        populateAddMenuItems(popup, wp, false);
        return popup;
    }

    /** Same items, but adds them to an existing JMenu (table uses this). */
    public static void populateAddMenu(JMenu parent, WorkspacePanel wp) {
        // For the table menu, do not include call graph items here as they are added separately
        populateAddMenuItems(parent, wp, true);
    }

    /**
     * Populates a JComponent (either JPopupMenu or JMenu) with "Add" actions.
     * @param parent The JComponent to populate.
     * @param wp The WorkspacePanel instance.
     * @param includeCallGraphItems whether to include "Callers" and "Callees" items.
     */
    private static void populateAddMenuItems(JComponent parent, WorkspacePanel wp, boolean includeCallGraphItems) {
        assert SwingUtilities.isEventDispatchThread();

        JMenuItem editMenuItem = new JMenuItem("Edit Files");
        editMenuItem.addActionListener(e -> {
            wp.performContextActionAsync(WorkspacePanel.ContextAction.EDIT, List.<ContextFragment>of());
        });
        // Only add Edit Files when git is present
        if (wp.getContextManager() != null && wp.getContextManager().getProject() != null && wp.getContextManager().getProject().hasGit()) {
            parent.add(editMenuItem);
        }

        JMenuItem readMenuItem = new JMenuItem("Read Files");
        readMenuItem.addActionListener(e -> {
            wp.performContextActionAsync(WorkspacePanel.ContextAction.READ, List.<ContextFragment>of());
        });
        parent.add(readMenuItem);

        JMenuItem summarizeMenuItem = new JMenuItem("Summarize Files");
        summarizeMenuItem.addActionListener(e -> {
            wp.performContextActionAsync(WorkspacePanel.ContextAction.SUMMARIZE, List.<ContextFragment>of());
        });
        parent.add(summarizeMenuItem);

        JMenuItem symbolMenuItem = new JMenuItem("Symbol Usage");
        symbolMenuItem.addActionListener(e -> {
            wp.findSymbolUsageAsync();
        });
        parent.add(symbolMenuItem);

        if (includeCallGraphItems) {
            // Optional: visually group these
            if (parent instanceof JPopupMenu popupMenu) {
                popupMenu.addSeparator();
            } else if (parent instanceof JMenu menu) {
                menu.addSeparator();
            }

            JMenuItem callersMenuItem = new JMenuItem("Callers");
            callersMenuItem.addActionListener(e -> {
                wp.findMethodCallersAsync();
            });
            parent.add(callersMenuItem);

            JMenuItem calleesMenuItem = new JMenuItem("Callees");
            calleesMenuItem.addActionListener(e -> {
                wp.findMethodCalleesAsync();
            });
            parent.add(calleesMenuItem);
        }
    }
}
