package ai.brokk.gui.dependencies;

import static java.util.Objects.requireNonNull;

import ai.brokk.analyzer.NodeJsDependencyHelper;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.gui.BorderUtils;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.Constants;
import ai.brokk.gui.WorkspacePanel;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.dialogs.ImportDependencyDialog;
import ai.brokk.gui.util.Icons;
import ai.brokk.project.AbstractProject;
import ai.brokk.util.Decompiler;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableRowSorter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Reusable panel for viewing and managing project dependencies. This is a refactoring of the ManageDependenciesDialog
 * content into a JPanel.
 */
public final class DependenciesPanel extends JPanel {

    public static interface DependencyLifecycleListener {
        void dependencyImportStarted(String name);

        void dependencyImportFinished(String name);
    }

    /**
     * Listener for dependency state changes (count changes due to add/remove/toggle). Fired after persistence
     * completes.
     */
    public interface DependencyStateChangeListener {
        /**
         * Called when the dependency state has changed and been persisted.
         *
         * @param newCount the new count of live (enabled) dependencies
         */
        void dependencyStateChanged(int newCount);
    }

    private static final Logger logger = LogManager.getLogger(DependenciesPanel.class);

    private final Chrome chrome;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final Map<String, ProjectFile> dependencyProjectFileMap = new HashMap<>();
    private final List<DependencyStateChangeListener> stateChangeListeners = new ArrayList<>();
    private boolean isProgrammaticChange = false;
    private boolean isInitialized = false;

    /**
     * Represents the state of a dependency's "Live" checkbox in the table.
     * Provides type safety instead of mixing Boolean and String values.
     */
    private enum LiveState {
        LIVE("Live"),
        NOT_LIVE(""),
        ENABLING("Loading..."),
        DISABLING("Unloading...");

        private final String displayText;

        LiveState(String displayText) {
            this.displayText = displayText;
        }

        public String getDisplayText() {
            return displayText;
        }

        public boolean isLive() {
            return this == LIVE || this == ENABLING;
        }

        public boolean isTransitioning() {
            return this == ENABLING || this == DISABLING;
        }
    }

    // UI pieces used to align the bottom area with WorkspacePanel
    private JPanel southContainerPanel;
    private JPanel addRemovePanel;
    private JPanel bottomSpacer;

    private MaterialButton addButton;
    private MaterialButton removeButton;
    private boolean controlsLocked = false;
    private @Nullable CompletableFuture<Void> inFlightToggleSave = null;

    private static class NumberRenderer extends DefaultTableCellRenderer {
        public NumberRenderer() {
            setHorizontalAlignment(RIGHT);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            if (value instanceof Number) {
                value = String.format("%,d", (Number) value);
            }
            return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        }
    }

    private static boolean isTruthyLive(Object v) {
        return v instanceof LiveState state && state.isLive();
    }

    private class LiveCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            if (!(value instanceof LiveState state)) {
                return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            }

            if (state.isTransitioning()) {
                // Show text label for transitioning states
                var lbl = new JLabel(state.getDisplayText());
                lbl.setHorizontalAlignment(CENTER);
                lbl.setOpaque(isSelected);
                if (isSelected) {
                    lbl.setBackground(table.getSelectionBackground());
                    lbl.setForeground(table.getSelectionForeground());
                }
                return lbl;
            } else {
                // Show checkbox for stable states
                var cb = new JCheckBox();
                cb.setSelected(state == LiveState.LIVE);
                cb.setHorizontalAlignment(CENTER);
                cb.setOpaque(true);
                cb.setEnabled(!controlsLocked);
                if (isSelected) {
                    cb.setBackground(table.getSelectionBackground());
                    cb.setForeground(table.getSelectionForeground());
                } else {
                    cb.setBackground(table.getBackground());
                    cb.setForeground(table.getForeground());
                }
                return cb;
            }
        }
    }

    private static class LiveStateCellEditor extends DefaultCellEditor {
        public LiveStateCellEditor() {
            super(new JCheckBox());
            var cb = (JCheckBox) getComponent();
            cb.setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellEditorComponent(
                JTable table, Object value, boolean isSelected, int row, int column) {
            var state = (value instanceof LiveState s) ? s : LiveState.NOT_LIVE;
            var cb = (JCheckBox)
                    super.getTableCellEditorComponent(table, state == LiveState.LIVE, isSelected, row, column);
            cb.setSelected(state == LiveState.LIVE);
            return cb;
        }

        @Override
        public Object getCellEditorValue() {
            var cb = (JCheckBox) getComponent();
            return cb.isSelected() ? LiveState.LIVE : LiveState.NOT_LIVE;
        }
    }

    public DependenciesPanel(Chrome chrome) {
        super(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Dependencies",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)));

        this.chrome = chrome;

        var contentPanel = new JPanel(new BorderLayout());

        Object[] columnNames = {"Live", "Name", "Files"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 0) return LiveState.class;
                if (columnIndex >= 2) return Long.class;
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                if (column != 0) return false;
                if (controlsLocked) return false;
                Object v = getValueAt(row, 0);
                // Only editable when in stable state (not transitioning)
                return v instanceof LiveState state && !state.isTransitioning();
            }
        };

        table = new JTable(tableModel) {
            @Override
            public @Nullable String getToolTipText(MouseEvent e) {
                var p = e.getPoint();
                int row = rowAtPoint(p);
                int col = columnAtPoint(p);
                if (row == -1 || col == -1) return null;
                int modelCol = convertColumnIndexToModel(col);
                // Only provide tooltip for the "Name" column (model index 1)
                if (modelCol != 1) return super.getToolTipText(e);

                // Return the same content as shown in the table cell (the Name column)
                Object v = getValueAt(row, col);
                return Objects.toString(v, null);
            }
        };
        var sorter = new TableRowSorter<>(tableModel) {
            @Override
            public void toggleSortOrder(int column) {
                var currentKeys = getSortKeys();
                // If this column is already the primary sort column, use the default toggle behavior
                if (!currentKeys.isEmpty() && currentKeys.get(0).getColumn() == column) {
                    super.toggleSortOrder(column);
                    return;
                }
                // For a newly-clicked column, default to DESC for LoC (model column 3), otherwise ASC.
                var defaultOrder = (column == 2) ? SortOrder.DESCENDING : SortOrder.ASCENDING;
                setSortKeys(List.of(new RowSorter.SortKey(column, defaultOrder)));
            }
        };
        table.setRowSorter(sorter);
        var sortKeys = new ArrayList<RowSorter.SortKey>();
        sortKeys.add(new RowSorter.SortKey(0, SortOrder.DESCENDING)); // Enabled first
        // Then by Files (model column 2).
        sortKeys.add(new RowSorter.SortKey(2, SortOrder.DESCENDING));
        sortKeys.add(new RowSorter.SortKey(1, SortOrder.ASCENDING)); // Then by name
        sorter.setSortKeys(sortKeys);

        table.setDefaultRenderer(Long.class, new NumberRenderer());

        TableColumnModel columnModel = table.getColumnModel();
        // Live checkbox column (keep narrow)
        columnModel.getColumn(0).setMaxWidth(columnModel.getColumn(0).getPreferredWidth());
        columnModel.getColumn(0).setCellRenderer(new LiveCellRenderer());
        columnModel.getColumn(0).setCellEditor(new LiveStateCellEditor());
        // Sort by live status (LIVE/ENABLING first, then NOT_LIVE/DISABLING)
        sorter.setComparator(0, (a, b) -> Boolean.compare(isTruthyLive(a), isTruthyLive(b)));
        // Name column width
        columnModel.getColumn(1).setPreferredWidth(200);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);

        // Ensure Tab/Shift+Tab move focus forward/backward out of the table (not just within cells)
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "transferFocusOut");
        table.getActionMap().put("transferFocusOut", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                table.transferFocus();
            }
        });
        table.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK), "transferFocusBackwardOut");
        table.getActionMap().put("transferFocusBackwardOut", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                table.transferFocusBackward();
            }
        });

        var scrollPane = new JScrollPane(table);
        // Ensure no viewport border/inset so the table content can touch the scroll pane border
        scrollPane.setViewportBorder(null);
        // Use the shared focus-aware border so the dependencies table matches the workspace table.
        BorderUtils.addFocusBorder(scrollPane, table);

        // Make the table fill the viewport vertically and remove internal spacing so its edges are flush.
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setBorder(null);

        contentPanel.add(scrollPane, BorderLayout.CENTER);

        // --- South Panel: Buttons (right aligned) ---
        southContainerPanel = new JPanel(new BorderLayout());

        // Add/Remove on the right
        addRemovePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, Constants.H_GAP, 0));
        addButton = new MaterialButton();
        addButton.setIcon(Icons.ADD);
        removeButton = new MaterialButton();
        removeButton.setIcon(Icons.REMOVE);
        // Allow our custom Tab handlers to run by disabling default focus traversal keys
        addButton.setFocusTraversalKeysEnabled(false);
        removeButton.setFocusTraversalKeysEnabled(false);
        addRemovePanel.add(addButton);
        addRemovePanel.add(removeButton);

        southContainerPanel.add(addRemovePanel, BorderLayout.EAST);

        // Ensure Tab/Shift+Tab on Add/Remove buttons move focus beyond Dependencies
        addButton.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "depsNext");
        addButton.getActionMap().put("depsNext", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                addButton.transferFocus();
            }
        });
        addButton
                .getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK), "depsPrev");
        addButton.getActionMap().put("depsPrev", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                addButton.transferFocusBackward();
            }
        });

        removeButton.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "depsNext");
        removeButton.getActionMap().put("depsNext", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                removeButton.transferFocus();
            }
        });
        removeButton
                .getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK), "depsPrev");
        removeButton.getActionMap().put("depsPrev", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                removeButton.transferFocusBackward();
            }
        });

        // Spacer to align with the workspace bottom summary area (kept invisible)
        bottomSpacer = new JPanel();
        bottomSpacer.setOpaque(false);
        southContainerPanel.add(bottomSpacer, BorderLayout.SOUTH);

        contentPanel.add(southContainerPanel, BorderLayout.SOUTH);

        // Let the surrounding split pane control the overall height.
        // Make the scroll pane prefer the same size used by the workspace table so behavior matches.
        scrollPane.setPreferredSize(new Dimension(600, 150));
        add(contentPanel, BorderLayout.CENTER);

        // --- Action Listeners ---
        addButton.addActionListener(e -> {
            var listener = new DependencyLifecycleListener() {
                @Override
                public void dependencyImportStarted(String name) {
                    setControlsLocked(true);
                    addPendingDependencyRow(name);
                }

                @Override
                public void dependencyImportFinished(String name) {
                    // Add the new dependency to the live set (project layer handles persistence and analyzer update)
                    var project = chrome.getProject();
                    var analyzer = chrome.getContextManager().getAnalyzerWrapper();
                    project.addLiveDependency(name, analyzer)
                            .whenComplete((result, ex) -> SwingUtilities.invokeLater(() -> {
                                if (ex != null) {
                                    logger.error("Error adding live dependency '{}'", name, ex);
                                }
                                // Reload the UI after the dependency is added
                                loadDependenciesAsync();
                                setControlsLocked(false);
                            }));
                }
            };
            var parentWindow = SwingUtilities.getWindowAncestor(DependenciesPanel.this);
            ImportDependencyDialog.show(chrome, parentWindow, listener);
        });

        removeButton.addActionListener(e -> removeSelectedDependency());
        removeButton.setEnabled(false);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                removeButton.setEnabled(!controlsLocked && table.getSelectedRow() != -1);
            }
        });

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showTablePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showTablePopup(e);
            }
        });

        // Re-compute totals whenever data changes or check-boxes toggle.
        // Also persist changes when the enabled checkbox (column 0) is toggled.
        tableModel.addTableModelListener(e -> {
            // Ignore header/structure change events
            if (e.getFirstRow() == TableModelEvent.HEADER_ROW) return;
            if (isProgrammaticChange) return;

            if (e.getColumn() == 0) {
                int first = e.getFirstRow();
                int last = e.getLastRow();
                for (int row = first; row <= last; row++) {
                    Object v = tableModel.getValueAt(row, 0);
                    // Only handle stable states (LIVE/NOT_LIVE) - ignore transitioning states
                    if (v instanceof LiveState state && !state.isTransitioning()) {
                        String depName = (String) tableModel.getValueAt(row, 1);
                        var prevState = state == LiveState.LIVE ? LiveState.NOT_LIVE : LiveState.LIVE;

                        // If an operation is already in-flight or any row is transitioning, revert this toggle.
                        if (controlsLocked
                                || (inFlightToggleSave != null && !inFlightToggleSave.isDone())
                                || anyRowTransitioning()) {
                            isProgrammaticChange = true;
                            tableModel.setValueAt(prevState, row, 0);
                            isProgrammaticChange = false;
                            return;
                        }

                        // Lock UI early and stop editing to ensure renderer updates.
                        setControlsLocked(true);

                        // Show transitioning state while saving
                        var transitionState = state == LiveState.LIVE ? LiveState.ENABLING : LiveState.DISABLING;
                        isProgrammaticChange = true;
                        tableModel.setValueAt(transitionState, row, 0);
                        isProgrammaticChange = false;

                        final int rowIndex = row;
                        final var newState = state;
                        final var revertState = prevState;
                        inFlightToggleSave = saveChangesAsync(Map.of(depName, state == LiveState.LIVE))
                                .whenComplete((r, ex) -> SwingUtilities.invokeLater(() -> {
                                    isProgrammaticChange = true;
                                    if (ex != null) {
                                        JOptionPane.showMessageDialog(
                                                DependenciesPanel.this,
                                                "Failed to save dependency changes:\n" + ex.getMessage(),
                                                "Error Saving Dependencies",
                                                JOptionPane.ERROR_MESSAGE);
                                        tableModel.setValueAt(revertState, rowIndex, 0);
                                    } else {
                                        tableModel.setValueAt(newState, rowIndex, 0);
                                    }
                                    isProgrammaticChange = false;
                                    inFlightToggleSave = null;
                                    // Unlock UI after save completes (success or failure).
                                    setControlsLocked(false);
                                }));
                    }
                }
            }
        });
    }

    /**
     * Registers a listener to be notified when dependency state changes (after persistence completes).
     */
    public void addDependencyStateChangeListener(DependencyStateChangeListener listener) {
        stateChangeListeners.add(listener);
    }

    /**
     * Removes a previously registered dependency state change listener.
     */
    public void removeDependencyStateChangeListener(DependencyStateChangeListener listener) {
        stateChangeListeners.remove(listener);
    }

    /**
     * Notifies all registered listeners that the dependency state has changed.
     */
    private void notifyDependencyStateChanged(int newCount) {
        for (var listener : stateChangeListeners) {
            try {
                listener.dependencyStateChanged(newCount);
            } catch (Exception ex) {
                logger.warn("Dependency state change listener threw exception", ex);
            }
        }
    }

    private void setControlsLocked(boolean locked) {
        controlsLocked = locked;
        addButton.setEnabled(!locked);
        removeButton.setEnabled(!locked && table.getSelectedRow() != -1);
        if (table.isEditing()) {
            var editor = table.getCellEditor();
            if (editor != null) editor.stopCellEditing();
        }
        table.repaint();
    }

    private boolean anyRowTransitioning() {
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            Object v = tableModel.getValueAt(i, 0);
            if (v instanceof LiveState state && state.isTransitioning()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ensures the panel is fully initialized. Safe to call multiple times (idempotent). This method should be called
     * instead of manually invoking addNotify(), which is a Swing lifecycle method.
     */
    public void ensureInitialized() {
        if (isInitialized) {
            return;
        }
        isInitialized = true;

        loadDependenciesAsync();

        // Ensure spacer size is set after initial layout
        SwingUtilities.invokeLater(this::updateBottomSpacer);

        // Update spacer when the Workspace layout changes
        var workspacePanel = chrome.getContextPanel();
        workspacePanel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updateBottomSpacer();
            }

            @Override
            public void componentShown(ComponentEvent e) {
                updateBottomSpacer();
            }
        });

        // Listen for explicit bottom-controls height changes from WorkspacePanel
        workspacePanel.addBottomControlsListener(new WorkspacePanel.BottomControlsListener() {
            @Override
            public void bottomControlsHeightChanged(int newHeight) {
                updateBottomSpacer();
            }
        });
    }

    @Override
    public void addNotify() {
        super.addNotify();
        ensureInitialized();
    }

    private void addPendingDependencyRow(String name) {
        tableModel.addRow(new Object[] {LiveState.ENABLING, name, 0L});
    }

    private void loadDependenciesAsync() {
        new DependenciesLoaderWorker().execute();
    }

    private static record AsyncLoadResult(Map<String, ProjectFile> map, List<Object[]> rows) {}

    private class DependenciesLoaderWorker extends SwingWorker<AsyncLoadResult, Void> {
        @Override
        protected AsyncLoadResult doInBackground() {
            var project = chrome.getProject();
            var allDeps = project.getAllOnDiskDependencies();
            var liveDeps = new HashSet<>(project.getLiveDependencies());

            var map = new HashMap<String, ProjectFile>();
            var rows = new ArrayList<Object[]>();

            for (var dep : allDeps) {
                String folderName = dep.getRelPath().getFileName().toString();
                var pkg = NodeJsDependencyHelper.readPackageJsonFromDir(dep.absPath());
                String displayName = (pkg != null) ? NodeJsDependencyHelper.displayNameFrom(pkg) : folderName;
                if (displayName.isEmpty()) displayName = folderName;

                map.put(displayName, dep);
                boolean isLive = liveDeps.stream().anyMatch(d -> d.root().equals(dep));
                rows.add(new Object[] {isLive ? LiveState.LIVE : LiveState.NOT_LIVE, displayName, Long.valueOf(0L)});
            }

            return new AsyncLoadResult(map, rows);
        }

        @Override
        protected void done() {
            isProgrammaticChange = true;
            try {
                var result = get();
                tableModel.setRowCount(0);
                dependencyProjectFileMap.clear();
                dependencyProjectFileMap.putAll(result.map());
                for (var row : result.rows()) {
                    tableModel.addRow(row);
                }
            } catch (ExecutionException | InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                isProgrammaticChange = false;
            }

            // Notify listeners of current dependency count (after load from disk)
            int count = chrome.getProject().getLiveDependencies().size();
            SwingUtilities.invokeLater(() -> notifyDependencyStateChanged(count));

            // count files in background
            new FileCountingWorker().execute();
        }
    }

    public CompletableFuture<Void> saveChangesAsync() {
        return saveChangesAsync(Map.of());
    }

    /**
     * Saves changes to the project's live dependencies asynchronously.
     *
     * @param overridesByName a map of dependency names to their desired live state (true for live, false for not live).
     *                        If a dependency name is present in this map, its value will override the current UI checkbox state
     *                        for that dependency during the toggle operation. This allows explicit control over which dependencies
     *                        are toggled, bypassing the current UI selection.
     * @return a CompletableFuture that completes when the save operation is finished.
     */
    private CompletableFuture<Void> saveChangesAsync(Map<String, Boolean> overridesByName) {
        // Snapshot the desired live set on the EDT to avoid accessing Swing model off-thread
        // Build from ALL rows in the table (both checked and unchecked), applying overrides where specified
        var newLiveDependencyTopLevelDirs = new HashSet<Path>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            String name = (String) tableModel.getValueAt(i, 1);

            // Determine if this dependency should be live:
            // 1. If an override is provided for this name, use it
            // 2. Otherwise, use the current checkbox state (handling "Loading"/"Unloading" as truthy)
            boolean isLive;
            if (overridesByName.containsKey(name)) {
                isLive = overridesByName.get(name);
            } else {
                Object checkboxValue = tableModel.getValueAt(i, 0);
                isLive = isTruthyLive(checkboxValue);
            }

            if (!isLive) continue;

            var pf = dependencyProjectFileMap.get(name);
            if (pf != null) {
                var depTopLevelDir = chrome.getProject()
                        .getMasterRootPathForConfig()
                        .resolve(AbstractProject.BROKK_DIR)
                        .resolve(AbstractProject.DEPENDENCIES_DIR)
                        .resolve(pf.getRelPath().getFileName());
                newLiveDependencyTopLevelDirs.add(depTopLevelDir);
            }
        }

        var project = chrome.getProject();
        var analyzer = chrome.getContextManager().getAnalyzerWrapper();
        return project.updateLiveDependencies(newLiveDependencyTopLevelDirs, analyzer)
                .whenComplete((result, ex) -> {
                    // Notify listeners on EDT after save completes (success or failure)
                    SwingUtilities.invokeLater(() -> {
                        int newCount = chrome.getProject().getLiveDependencies().size();
                        notifyDependencyStateChanged(newCount);
                    });
                });
    }

    private class FileCountingWorker extends SwingWorker<Void, Object[]> {
        @Override
        protected Void doInBackground() {
            int rowCount = tableModel.getRowCount();
            for (int i = 0; i < rowCount; i++) {
                String name = (String) tableModel.getValueAt(i, 1);
                var pf = dependencyProjectFileMap.get(name);
                requireNonNull(pf);

                try (var pathStream = Files.walk(pf.absPath())) {
                    long fileCount = pathStream.filter(Files::isRegularFile).count();
                    publish(new Object[] {i, fileCount});
                } catch (IOException e) {
                    publish(new Object[] {i, 0L});
                }
            }
            return null;
        }

        @Override
        protected void process(List<Object[]> chunks) {
            isProgrammaticChange = true;
            try {
                for (Object[] chunk : chunks) {
                    int row = (int) chunk[0];
                    long files = (long) chunk[1];
                    tableModel.setValueAt(files, row, 2);
                }
            } finally {
                isProgrammaticChange = false;
            }
        }
    }

    private void updateBottomSpacer() {
        try {
            var wp = chrome.getContextPanel();
            int target = wp.getBottomControlsPreferredHeight();
            int controls = addRemovePanel.getPreferredSize().height;
            int filler = Math.max(0, target - controls);
            bottomSpacer.setPreferredSize(new Dimension(0, filler));
            bottomSpacer.setMinimumSize(new Dimension(0, filler));
            southContainerPanel.revalidate();
            southContainerPanel.repaint();
        } catch (Exception e) {
            logger.debug("Error updating dependencies bottom spacer", e);
        }
    }

    private void showTablePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int viewRow = table.rowAtPoint(e.getPoint());
        if (viewRow < 0) {
            return;
        }
        int viewCol = table.columnAtPoint(e.getPoint());
        if (viewCol < 0) {
            return;
        }

        table.setRowSelectionInterval(viewRow, viewRow);
        int modelRow = table.convertRowIndexToModel(viewRow);

        var state = tableModel.getValueAt(modelRow, 0);
        boolean isLive = state instanceof LiveState ls && ls == LiveState.LIVE;

        var menu = new JPopupMenu();
        var summarizeItem = new JMenuItem("Summarize All Files");
        summarizeItem.setEnabled(isLive);
        summarizeItem.addActionListener(ev -> summarizeDependencyForRow(modelRow));
        menu.add(summarizeItem);
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    private void summarizeDependencyForRow(int modelRow) {
        Object nameObj = tableModel.getValueAt(modelRow, 1);
        if (!(nameObj instanceof String depName)) {
            return;
        }
        var pf = dependencyProjectFileMap.get(depName);
        if (pf == null) {
            return;
        }
        // Only allow summarizing for Live dependencies
        var liveState = tableModel.getValueAt(modelRow, 0);
        if (!(liveState instanceof LiveState ls && ls == LiveState.LIVE)) {
            return;
        }

        var project = chrome.getProject();
        var depOpt = project.getLiveDependencies().stream()
                .filter(d -> d.root().equals(pf))
                .findFirst();
        if (depOpt.isEmpty()) {
            return;
        }
        var dep = depOpt.get();

        var cm = chrome.getContextManager();
        cm.submitContextTask(() -> {
            cm.addSummaries(dep.files(), Set.of());
        });
    }

    private void removeSelectedDependency() {
        int selectedRowInView = table.getSelectedRow();
        if (selectedRowInView == -1) {
            return;
        }
        int selectedRowInModel = table.convertRowIndexToModel(selectedRowInView);

        String depName = (String) tableModel.getValueAt(selectedRowInModel, 1);
        int choice = chrome.showConfirmDialog(
                this,
                "Are you sure you want to delete the dependency '" + depName + "'?\nThis action cannot be undone.",
                "Confirm Deletion",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (choice == JOptionPane.YES_OPTION) {
            var pf = dependencyProjectFileMap.get(depName);
            if (pf != null) {
                var cm = chrome.getContextManager();
                cm.getAnalyzerWrapper().pause();
                try {
                    Decompiler.deleteDirectoryRecursive(pf.absPath());
                    loadDependenciesAsync();
                    // Persist changes after successful deletion and reload.
                    saveChangesAsync();
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(
                            this,
                            "Error deleting dependency '" + depName + "':\n" + ex.getMessage(),
                            "Deletion Error",
                            JOptionPane.ERROR_MESSAGE);
                } finally {
                    cm.getAnalyzerWrapper().resume();
                }
            }
        }
    }

    // Public getters for focus traversal policy
    public JTable getDependencyTable() {
        return table;
    }

    public MaterialButton getAddButton() {
        return addButton;
    }

    public MaterialButton getRemoveButton() {
        return removeButton;
    }
}
