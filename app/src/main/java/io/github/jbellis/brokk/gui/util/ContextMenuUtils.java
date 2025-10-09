package io.github.jbellis.brokk.gui.util;

import io.github.jbellis.brokk.AnalyzerWrapper;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.TableUtils;
import io.github.jbellis.brokk.gui.TableUtils.FileReferenceList.FileReferenceData;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Set;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Utility class for creating and displaying context menus for file references. */
public final class ContextMenuUtils {
    private static final Logger logger = LogManager.getLogger(ContextMenuUtils.class);

    /**
     * Handles mouse clicks on file reference badges in a table. Shows the appropriate context menu or overflow popup
     * based on the click location and type.
     *
     * @param e The mouse event
     * @param table The table containing file reference badges
     * @param chrome The Chrome instance for UI integration
     * @param onRefreshSuggestions Runnable to call when "Refresh Suggestions" is selected
     */
    public static void handleFileReferenceClick(
            MouseEvent e, JTable table, Chrome chrome, Runnable onRefreshSuggestions) {
        handleFileReferenceClick(e, table, chrome, onRefreshSuggestions, 0);
    }

    /**
     * Handles mouse clicks on file reference badges in a table with a specified column index. Shows the appropriate
     * context menu or overflow popup based on the click location and type.
     *
     * @param e The mouse event
     * @param table The table containing file reference badges
     * @param chrome The Chrome instance for UI integration
     * @param onRefreshSuggestions Runnable to call when "Refresh Suggestions" is selected
     * @param columnIndex The column index containing the file references
     */
    public static void handleFileReferenceClick(
            MouseEvent e, JTable table, Chrome chrome, Runnable onRefreshSuggestions, int columnIndex) {
        assert SwingUtilities.isEventDispatchThread();

        Point p = e.getPoint();
        int row = table.rowAtPoint(p);
        if (row < 0) return;

        // Request focus and select the row containing the badge
        table.requestFocusInWindow();
        table.setRowSelectionInterval(row, row);

        // Extract file references - handle both List<FileReferenceData> and DescriptionWithReferences
        List<FileReferenceData> fileRefs;
        Object cellValue = table.getValueAt(row, columnIndex);

        if (cellValue instanceof List) {
            // InstructionsPanel case - direct list of file references
            @SuppressWarnings("unchecked")
            var directList = (List<FileReferenceData>) cellValue;
            fileRefs = directList;
        } else if (cellValue
                instanceof io.github.jbellis.brokk.gui.WorkspacePanel.DescriptionWithReferences descWithRefs) {
            // WorkspacePanel case - extract from DescriptionWithReferences record
            fileRefs = descWithRefs.fileReferences();
        } else {
            // Unsupported cell value type
            return;
        }

        if (fileRefs == null || fileRefs.isEmpty()) return;

        // Get the renderer and extract visible/hidden files using reflection
        Component renderer = table.prepareRenderer(table.getCellRenderer(row, columnIndex), row, columnIndex);

        // Extract needed data from renderer using pattern matching
        List<FileReferenceData> visibleFiles;
        List<FileReferenceData> hiddenFiles = List.of();
        boolean hasOverflow = false;

        if (renderer instanceof TableUtils.FileReferenceList.AdaptiveFileReferenceList afl) {
            // InstructionsPanel case - direct AdaptiveFileReferenceList
            visibleFiles = afl.getVisibleFiles();
            hasOverflow = afl.hasOverflow();

            // BUGFIX: If renderer reports no overflow but we have more files than visible,
            // recalculate overflow state. This handles cases where renderer wasn't properly laid out.
            if (!hasOverflow && fileRefs.size() > visibleFiles.size()) {
                hasOverflow = true;
                hiddenFiles = fileRefs.subList(visibleFiles.size(), fileRefs.size());
            } else if (hasOverflow) {
                hiddenFiles = afl.getHiddenFiles();
            }
        } else if (renderer instanceof javax.swing.JPanel panel) {
            // WorkspacePanel case - AdaptiveFileReferenceList is inside a JPanel
            TableUtils.FileReferenceList.AdaptiveFileReferenceList foundAfl = null;
            for (java.awt.Component c : panel.getComponents()) {
                if (c instanceof TableUtils.FileReferenceList.AdaptiveFileReferenceList afl) {
                    foundAfl = afl;
                    break;
                }
            }

            if (foundAfl != null) {
                visibleFiles = foundAfl.getVisibleFiles();
                hasOverflow = foundAfl.hasOverflow();

                // BUGFIX: Apply same overflow detection fix for WorkspacePanel
                if (!hasOverflow && fileRefs.size() > visibleFiles.size()) {
                    hasOverflow = true;
                    hiddenFiles = fileRefs.subList(visibleFiles.size(), fileRefs.size());
                } else if (hasOverflow) {
                    hiddenFiles = foundAfl.getHiddenFiles();
                }
            } else {
                // JPanel doesn't contain AdaptiveFileReferenceList
                visibleFiles = fileRefs;
            }
        } else {
            // Fallback if not the expected renderer type
            visibleFiles = fileRefs;
            // Log the issue but continue (avoid breaking the UI)
            System.err.println(
                    "Unexpected renderer type: " + renderer.getClass().getName());
        }

        // Check what kind of mouse event we're handling
        if (e.isPopupTrigger()) {
            // Right-click (context menu)
            var targetRef = TableUtils.findClickedReference(p, row, columnIndex, table);

            // Right-click on overflow badge?
            if (targetRef == null && hasOverflow) {
                TableUtils.showOverflowPopup(chrome, table, row, columnIndex, hiddenFiles);
                e.consume(); // Prevent further listeners from acting on this event
                return;
            }

            // Default to first file if click wasn't on a specific badge
            if (targetRef == null) targetRef = fileRefs.get(0);

            // Show the context menu near the mouse click location
            showFileRefMenu(table, e.getX(), e.getY(), targetRef, chrome, onRefreshSuggestions);
            e.consume(); // Prevent further listeners from acting on this event
        } else if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
            // Left-click
            var targetRef = TableUtils.findClickedReference(p, row, columnIndex, table);
            logger.debug(
                    "Left-click on file badges - targetRef={}, hasOverflow={}, visibleFiles={}, totalFiles={}",
                    targetRef != null ? targetRef.getFileName() : "null",
                    hasOverflow,
                    visibleFiles.size(),
                    fileRefs.size());

            // If no visible badge was clicked, check if it was an overflow badge click
            if (targetRef == null && hasOverflow) {
                // Check if the click is actually on the overflow badge area
                boolean isOverflowClick = TableUtils.isClickOnOverflowBadge(p, row, columnIndex, table);
                logger.debug("Overflow badge click check - isOverflowClick={}", isOverflowClick);
                if (isOverflowClick) {
                    // Show the overflow popup with only the hidden files
                    logger.debug("Showing overflow popup with {} hidden files", hiddenFiles.size());
                    TableUtils.showOverflowPopup(chrome, table, row, columnIndex, hiddenFiles);
                    e.consume(); // Prevent further listeners from acting on this event
                }
            }
        }
    }

    // Private constructor to prevent instantiation
    private ContextMenuUtils() {}

    /**
     * Shows a context menu for a file reference.
     *
     * @param owner The component that owns the popup (where it will be displayed)
     * @param fileRefData The file reference data for which to show the menu
     * @param chrome The Chrome instance for UI integration
     * @param onRefreshSuggestions Runnable to call when "Refresh Suggestions" is selected
     */
    public static void showFileRefMenu(
            Component owner, Object fileRefData, Chrome chrome, Runnable onRefreshSuggestions) {
        showFileRefMenu(owner, 0, 0, fileRefData, chrome, onRefreshSuggestions);
    }

    /**
     * Shows a context menu for a file reference at the specified position.
     *
     * @param owner The component that owns the popup (where it will be displayed)
     * @param x The x position for the menu relative to the owner
     * @param y The y position for the menu relative to the owner
     * @param fileRefData The file reference data for which to show the menu
     * @param chrome The Chrome instance for UI integration
     * @param onRefreshSuggestions Runnable to call when "Refresh Suggestions" is selected
     */
    public static void showFileRefMenu(
            Component owner, int x, int y, Object fileRefData, Chrome chrome, Runnable onRefreshSuggestions) {
        // Convert to our FileReferenceData - we know it's always this type from all callers
        TableUtils.FileReferenceList.FileReferenceData targetRef =
                (TableUtils.FileReferenceList.FileReferenceData) fileRefData;

        var cm = chrome.getContextManager();
        JPopupMenu menu = new JPopupMenu();

        JMenuItem showContentsItem = new JMenuItem("Show Contents");
        showContentsItem.addActionListener(e1 -> {
            if (targetRef.getRepoFile() != null) {
                chrome.openFragmentPreview(new ContextFragment.ProjectPathFragment(targetRef.getRepoFile(), cm));
            }
        });
        menu.add(showContentsItem);
        menu.addSeparator();

        // Edit option
        JMenuItem editItem = new JMenuItem("Edit " + targetRef.getFullPath());
        editItem.addActionListener(e1 -> {
            withTemporaryListenerDetachment(chrome, () -> {
                if (targetRef.getRepoFile() != null) {
                    cm.addFiles(List.of(targetRef.getRepoFile()));
                } else {
                    chrome.toolError("Cannot edit file: " + targetRef.getFullPath() + " - no ProjectFile available");
                }
            });
        });
        // Disable for dependency projects
        if (!cm.getProject().hasGit()) {
            editItem.setEnabled(false);
            editItem.setToolTipText("Editing not available without Git");
        }
        menu.add(editItem);

        // Summarize option
        JMenuItem summarizeItem = new JMenuItem("Summarize " + targetRef.getFullPath());
        summarizeItem.addActionListener(e1 -> {
            if (!cm.getAnalyzerWrapper().isReady()) {
                cm.getIo()
                        .systemNotify(
                                AnalyzerWrapper.ANALYZER_BUSY_MESSAGE,
                                AnalyzerWrapper.ANALYZER_BUSY_TITLE,
                                JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            withTemporaryListenerDetachment(chrome, () -> {
                if (targetRef.getRepoFile() == null) {
                    chrome.toolError("Cannot summarize: " + targetRef.getFullPath()
                            + " - ProjectFile information not available");
                } else {
                    boolean success = cm.addSummaries(Set.of(targetRef.getRepoFile()), Set.of());
                    if (!success) {
                        chrome.toolError("No summarizable code found");
                    }
                }
            });
        });
        menu.add(summarizeItem);
        menu.addSeparator();

        JMenuItem refreshSuggestionsItem = new JMenuItem("Refresh Suggestions");
        refreshSuggestionsItem.addActionListener(e1 -> onRefreshSuggestions.run());
        menu.add(refreshSuggestionsItem);

        // Theme management will be handled by the caller
        menu.show(owner, x, y);
    }

    /**
     * Shows a context menu for a context fragment at the specified position.
     *
     * @param owner The component that owns the popup (where it will be displayed)
     * @param x The x position for the menu relative to the owner
     * @param y The y position for the menu relative to the owner
     * @param fragment The context fragment for which to show the menu
     * @param chrome The Chrome instance for UI integration
     */
    public static void showContextFragmentMenu(Component owner, int x, int y, ContextFragment fragment, Chrome chrome) {
        var cm = chrome.getContextManager();
        var menu = new JPopupMenu();

        // "Show Contents" is applicable to almost all fragments
        var showContentsItem = new JMenuItem("Show Contents");
        showContentsItem.addActionListener(e -> chrome.openFragmentPreview(fragment));
        menu.add(showContentsItem);
        menu.addSeparator();

        // File-specific actions
        if (fragment instanceof ContextFragment.PathFragment pathFragment
                && pathFragment.file() instanceof ProjectFile projectFile) {
            var showInTreeItem = new JMenuItem("Show in Project Tree");
            showInTreeItem.addActionListener(e -> chrome.getProjectFilesPanel().showFileInTree(projectFile));
            menu.add(showInTreeItem);
            menu.addSeparator();

            // Edit option
            var editItem = new JMenuItem("Edit " + projectFile);
            editItem.addActionListener(e1 -> {
                withTemporaryListenerDetachment(chrome, () -> {
                    cm.addFiles(List.of(projectFile));
                });
            });
            // Disable for dependency projects
            if (!cm.getProject().hasGit()) {
                editItem.setEnabled(false);
                editItem.setToolTipText("Editing not available without Git");
            }
            menu.add(editItem);

            // Summarize option
            var summarizeItem = new JMenuItem("Summarize " + projectFile);
            summarizeItem.addActionListener(e1 -> {
                if (!cm.getAnalyzerWrapper().isReady()) {
                    cm.getIo()
                            .systemNotify(
                                    AnalyzerWrapper.ANALYZER_BUSY_MESSAGE,
                                    AnalyzerWrapper.ANALYZER_BUSY_TITLE,
                                    JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                withTemporaryListenerDetachment(chrome, () -> {
                    boolean success = cm.addSummaries(Set.of(projectFile), Set.of());
                    if (!success) {
                        chrome.toolError("No summarizable code found");
                    }
                });
            });
            menu.add(summarizeItem);
            menu.addSeparator();
        }

        // "Remove" is always applicable
        var removeItem = new JMenuItem("Remove");
        removeItem.addActionListener(e -> cm.drop(List.of(fragment)));
        menu.add(removeItem);

        menu.show(owner, x, y);
    }

    /** Helper method to detach context listener temporarily while performing operations. */
    private static void withTemporaryListenerDetachment(Chrome chrome, Runnable action) {
        // Access the contextManager from Chrome and call submitContextTask on it
        chrome.getContextManager().submitContextTask(action);
    }
}
