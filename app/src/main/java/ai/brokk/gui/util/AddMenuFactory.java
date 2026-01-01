package ai.brokk.gui.util;

import ai.brokk.context.ContextFragment;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.ContextActionsHandler;
import ai.brokk.gui.dialogs.ImportDependencyDialog;
import java.util.List;
import javax.swing.*;

public final class AddMenuFactory {
    private AddMenuFactory() {}

    /** Builds the Add popup that contains Edit, Read, Summarize, Symbol Usage */
    public static JPopupMenu buildAddPopup(ContextActionsHandler actions) {
        var popup = new JPopupMenu();
        // For the attach/@-triggered menu we do NOT include the import-dependency option.
        populateAddMenuItems(popup, actions, /*includeCallGraphItems=*/ false, /*includeImportDependency=*/ false);
        return popup;
    }

    /** Same items, but adds them to an existing JMenu (table uses this). */
    public static void populateAddMenu(JMenu parent, ContextActionsHandler actions) {
        // For the table menu, include import-dependency and call-graph items
        populateAddMenuItems(parent, actions, /*includeCallGraphItems=*/ true, /*includeImportDependency=*/ true);
    }

    private static void addSeparator(JComponent parent) {
        if (parent instanceof JMenu menu) {
            menu.addSeparator();
        } else if (parent instanceof JPopupMenu popupMenu) {
            popupMenu.addSeparator();
        }
    }

    /**
     * Populates a JComponent (either JPopupMenu or JMenu) with "Add" actions.
     *
     * @param parent The JComponent to populate.
     * @param actions The ContextActionsHandler instance.
     * @param includeCallGraphItems whether to include "Callers" and "Callees" items.
     * @param includeImportDependency whether to include the "Import Dependency..." item.
     */
    private static void populateAddMenuItems(
            JComponent parent,
            ContextActionsHandler actions,
            boolean includeCallGraphItems,
            boolean includeImportDependency) {
        assert SwingUtilities.isEventDispatchThread();

        JMenuItem editMenuItem = new JMenuItem("Edit Files");
        editMenuItem.addActionListener(e -> {
            actions.performContextActionAsync(ContextActionsHandler.ContextAction.EDIT, List.<ContextFragment>of());
        });
        // Only add Edit Files when git is present
        if (actions.getContextManager().getProject().hasGit()) {
            parent.add(editMenuItem);
        }

        JMenuItem summarizeMenuItem = new JMenuItem("Summarize Files");
        summarizeMenuItem.addActionListener(e -> {
            actions.performContextActionAsync(
                    ContextActionsHandler.ContextAction.SUMMARIZE, List.<ContextFragment>of());
        });
        parent.add(summarizeMenuItem);

        addSeparator(parent);

        JMenuItem symbolMenuItem = new JMenuItem("Symbol Usage");
        symbolMenuItem.addActionListener(e -> {
            actions.findSymbolUsageAsync();
        });
        parent.add(symbolMenuItem);

        if (includeCallGraphItems) {
            addSeparator(parent);

            JMenuItem callersMenuItem = new JMenuItem("Callers");
            callersMenuItem.addActionListener(e -> {
                actions.findMethodCallersAsync();
            });
            parent.add(callersMenuItem);

            JMenuItem calleesMenuItem = new JMenuItem("Callees");
            calleesMenuItem.addActionListener(e -> {
                actions.findMethodCalleesAsync();
            });
            parent.add(calleesMenuItem);
        }

        // Only add the separator + Import Dependency item when requested.
        if (includeImportDependency) {
            addSeparator(parent);
            JMenuItem dependencyItem = new JMenuItem("Import Dependency...");
            dependencyItem.addActionListener(e -> ImportDependencyDialog.show(
                    (Chrome) actions.getContextManager().getIo()));
            parent.add(dependencyItem);
        }
    }
}
