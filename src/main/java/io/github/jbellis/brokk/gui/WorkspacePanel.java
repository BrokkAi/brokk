package io.github.jbellis.brokk.gui;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.CodeUnitType;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.dialogs.CallGraphDialog;
import io.github.jbellis.brokk.gui.dialogs.MultiFileSelectionDialog;
import io.github.jbellis.brokk.gui.dialogs.MultiFileSelectionDialog.SelectionMode;
import io.github.jbellis.brokk.gui.dialogs.SymbolSelectionDialog;
import io.github.jbellis.brokk.gui.util.AddMenuFactory;
import io.github.jbellis.brokk.gui.util.ContextMenuUtils;
import io.github.jbellis.brokk.prompts.CopyExternalPrompts;
import io.github.jbellis.brokk.tools.WorkspaceTools;
import io.github.jbellis.brokk.util.HtmlToMarkdown;
import io.github.jbellis.brokk.util.Messages;
import io.github.jbellis.brokk.util.StackTrace;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.Arrays;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class WorkspacePanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(WorkspacePanel.class);
    private final String EMPTY_CONTEXT = "Empty Workspace--use Edit or Read or Summarize to add content";

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .writeTimeout(1, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    /**
     * Enum representing the different types of context actions that can be performed.
     */
    public enum ContextAction {
        EDIT, READ, SUMMARIZE, DROP, COPY, PASTE
    }

    public enum PopupMenuMode { FULL, COPY_ONLY }

    // Columns
    private final int FILES_REFERENCED_COLUMN = 2;
    private final int FRAGMENT_COLUMN = 3;

    // Parent references
    private final Chrome chrome;
    private final ContextManager contextManager;

    private JTable contextTable;

    private final PopupMenuMode popupMenuMode;
    private JPanel locSummaryPanel;
    private JPanel warningPanel; // Panel for warning messages
    private boolean workspaceCurrentlyEditable = true;
    private Context currentContext;

    // Buttons
    // Table popup menu (when no row is selected)
    private JPopupMenu tablePopupMenu;

    /**
     * Primary constructor allowing menu-mode selection
     */
    public WorkspacePanel(Chrome chrome,
                          ContextManager contextManager,
                          PopupMenuMode popupMenuMode) {
        super(new BorderLayout());
        assert chrome != null;
        assert contextManager != null;

        this.chrome = chrome;
        this.contextManager = contextManager;
        this.popupMenuMode = popupMenuMode;

        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Workspace",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        buildContextPanel();

        ((JLabel) locSummaryPanel.getComponent(0)).setText(EMPTY_CONTEXT);
        setWorkspaceEditable(true); // Set initial state
    }

    /** Convenience constructor – defaults to FULL popup menu */
    public WorkspacePanel(Chrome chrome, ContextManager contextManager) {
        this(chrome, contextManager, PopupMenuMode.FULL);
    }

    /**
     * Build the context panel (unified table + action buttons).
     */
    private void buildContextPanel() {
        DefaultTableModel tableModel = new DefaultTableModel(new Object[]{"LOC", "Description", "Files Referenced", "Fragment"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return switch (columnIndex) {
                    case 0 -> Integer.class;
                    case 1 -> String.class;
                    case 2 -> List.class; // Our references column
                    case 3 -> ContextFragment.class;
                    default -> Object.class;
                };
            }
        };

        contextTable = new JTable(tableModel) {
            @Override
            public Component prepareRenderer(javax.swing.table.TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);
                if (c != null) {
                    c.setEnabled(workspaceCurrentlyEditable);
                }
                return c;
            }
        };

        // Add custom cell renderer for the "Description" column
        contextTable.getColumnModel().getColumn(1).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                var c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (value != null && value.toString().startsWith("✏️")) {
                    setFont(getFont().deriveFont(Font.ITALIC));
                } else {
                    setFont(getFont().deriveFont(Font.PLAIN));
                }
                return c;
            }
        });

        // Files Referenced column: use our FileReferencesTableCellRenderer
        var fileRenderer = new TableUtils.FileReferencesTableCellRenderer();
        contextTable.getColumnModel().getColumn(FILES_REFERENCED_COLUMN).setCellRenderer(fileRenderer);

        // Dynamically set row height based on renderer's preferred size
        contextTable.setRowHeight(TableUtils.measuredBadgeRowHeight(contextTable));

        // Hide the FRAGMENT_COLUMN from view
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setMinWidth(0);
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setMaxWidth(0);
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setWidth(0);

        // Column widths
        contextTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        contextTable.getColumnModel().getColumn(0).setMaxWidth(100);
        contextTable.getColumnModel().getColumn(1).setPreferredWidth(230);
        contextTable.getColumnModel().getColumn(2).setPreferredWidth(250);

        // Add mouse listener to handle file reference badge clicks
        contextTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeHandleFileRefClick(e);
            }
            
            @Override
            public void mouseReleased(MouseEvent e) {
                maybeHandleFileRefClick(e);
            }
            
            private void maybeHandleFileRefClick(MouseEvent e) {
                int col = contextTable.columnAtPoint(e.getPoint());
                if (col == FILES_REFERENCED_COLUMN) {
                    ContextMenuUtils.handleFileReferenceClick(
                            e,
                            contextTable,
                            chrome,
                            () -> {}, // Workspace doesn't need to refresh suggestions
                            FILES_REFERENCED_COLUMN
                    );
                }
            }
        });
        
        // Add mouse motion for tooltips
        contextTable.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int row = contextTable.rowAtPoint(e.getPoint());
                int col = contextTable.columnAtPoint(e.getPoint());
                if (row >= 0 && col == FILES_REFERENCED_COLUMN) {
                    var value = contextTable.getValueAt(row, col);
                    if (value != null) {
                        // Show file references in a multiline tooltip
                        @SuppressWarnings("unchecked") List<TableUtils.FileReferenceList.FileReferenceData> refs = (List<TableUtils.FileReferenceList.FileReferenceData>) value;
                        if (!refs.isEmpty()) {
                            var sb = new StringBuilder("<html>");
                            for (TableUtils.FileReferenceList.FileReferenceData r : refs) {
                                sb.append(r.getFullPath()).append("<br>");
                            }
                            sb.append("</html>");
                            contextTable.setToolTipText(sb.toString());
                            return;
                        }
                    }
                } else if (row >= 0 && col == 1) {
                    // Show full description
                    var value = contextTable.getValueAt(row, col);
                    if (value != null) {
                        contextTable.setToolTipText(value.toString());
                        return;
                    }
                }
                contextTable.setToolTipText(null);
            }
        });

        // Add double-click to open fragment preview
        contextTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = contextTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        var fragment = (ContextFragment) contextTable.getModel().getValueAt(row, FRAGMENT_COLUMN);
                        if (fragment != null) {
                            showFragmentPreview(fragment);
                        }
                    }
                }
            }
        });

        // Create a single JPopupMenu for the table
        JPopupMenu contextMenu = new JPopupMenu();
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(contextMenu);
        } else {
            // Register this popup menu later when the theme manager is available
            SwingUtilities.invokeLater(() -> {
                if (chrome.themeManager != null) {
                    chrome.themeManager.registerPopupMenu(contextMenu);
                }
            });
        }

        // Add a mouse listener so we control exactly when the popup shows
        contextTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handlePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handlePopup(e);
            }

            private void handlePopup(MouseEvent e) {
                if (e.isConsumed()) return; // Respect if event was already handled
                if (e.isPopupTrigger()) {
                    int row = contextTable.rowAtPoint(e.getPoint());
                    contextTable.columnAtPoint(e.getPoint());

                    // Clear the menu and rebuild according to row/column
                    contextMenu.removeAll();

                    if (popupMenuMode == PopupMenuMode.COPY_ONLY) {
                        if (row >= 0) {
                            if (contextTable.getSelectedRowCount() == 0
                                    || !Arrays.stream(contextTable.getSelectedRows()).anyMatch(r -> r == row)) {
                                contextTable.setRowSelectionInterval(row, row);
                            }
                            JMenuItem copyItem = new JMenuItem("Copy to Active Workspace");
                            copyItem.addActionListener(ev -> {
                                var selectedFragments = getSelectedFragments();
                                contextManager.addFilteredToContextAsync(currentContext, selectedFragments);
                            });
                            contextMenu.add(copyItem);
                            if (chrome.themeManager != null) {
                                chrome.themeManager.registerPopupMenu(contextMenu);
                            }
                            contextMenu.show(contextTable, e.getX(), e.getY());
                        }
                        return;
                    }

                    ContextFragment selected = null;
                    if (row >= 0) {
                        // Select the row only if:
                        // 1. No rows are currently selected, or
                        // 2. Only one row is selected and it's not the row we clicked on
                        if (contextTable.getSelectedRowCount() == 0 || (contextTable.getSelectedRowCount() == 1 && contextTable.getSelectedRow() != row)) {
                            contextTable.setRowSelectionInterval(row, row);
                        }
                        selected = (ContextFragment) contextTable.getModel().getValueAt(row, FRAGMENT_COLUMN);
                    }

                    // Assign to effectively final variable for use in lambdas
                    final ContextFragment fragmentToShow = selected;

                    if (row >= 0) {
                        // Show Contents as the first action
                        JMenuItem showContentsItem = new JMenuItem("Show Contents");
                        // Use the effectively final variable in the lambda
                        showContentsItem.addActionListener(e1 -> showFragmentPreview(fragmentToShow));
                        contextMenu.add(showContentsItem);
                        contextMenu.addSeparator();

                        // show "View History" only if it's a ProjectPathFragment and Git is available
                        boolean hasGit = contextManager != null && contextManager.getProject() != null
                                && contextManager.getProject().hasGit();
                        if (hasGit && fragmentToShow.getType() == ContextFragment.FragmentType.PROJECT_PATH) {
                            fragmentToShow.files().stream()
                                .findFirst()
                                .ifPresent(projectFile -> {
                                    JMenuItem viewHistoryItem = new JMenuItem("View History");
                                    viewHistoryItem.addActionListener(ev -> chrome.getGitPanel().addFileHistoryTab(projectFile));
                                    contextMenu.add(viewHistoryItem);
                                });
                        } else if (fragmentToShow.getType() == ContextFragment.FragmentType.HISTORY) {
                            var cf = (ContextFragment.HistoryFragment) fragmentToShow;
                            // Add Compress History option for conversation fragment
                            JMenuItem compressHistoryItem = new JMenuItem("Compress History");
                            compressHistoryItem.addActionListener(e1 -> {
                                // Call ContextManager to compress history
                                contextManager.compressHistoryAsync();
                            });
                            contextMenu.add(compressHistoryItem);
                            // Only enable if uncompressed entries exist
                            var uncompressedExists = cf.entries().stream().anyMatch(entry -> !entry.isCompressed());
                            compressHistoryItem.setEnabled(uncompressedExists);
                        }

                        // Row popup options
                        {
                            contextMenu.addSeparator();
                            var selectedFragments = getSelectedFragments(); // Get selected fragments once

                            // Special case: Single ProjectPathFragment selected -> show specific actions for it
                            if (selectedFragments.size() == 1 && selectedFragments.getFirst().getType() == ContextFragment.FragmentType.PROJECT_PATH) {
                                selectedFragments.getFirst().files().stream()
                                    .findFirst()
                                    .ifPresent(projectFile -> {
                                        var fileData = new TableUtils.FileReferenceList.FileReferenceData(
                                                projectFile.getFileName(),
                                                projectFile.toString(),
                                                projectFile
                                        );
                                        // Add specific actions using existing helpers
                                        contextMenu.add(buildAddMenuItem(fileData)); // Handles edit for single tracked file
                                        contextMenu.add(buildReadMenuItem(fileData));
                                        contextMenu.add(buildSummarizeMenuItem(fileData));
                                    });
                            } else {
                                // Default: Show "All References" actions, enabled based on file presence and track status
                                var project = contextManager.getProject();
                                Set<BrokkFile> allFiles = selectedFragments.stream()
                                        .flatMap(frag -> frag.files().stream())
                                        .collect(Collectors.toSet());

                                boolean allFilesAreTrackedProjectFiles = !allFiles.isEmpty() && allFiles.stream().allMatch(f ->
                                        f instanceof ProjectFile pf && // This instanceof is on BrokkFile, not ContextFragment, so it's okay
                                        project.getRepo().getTrackedFiles().contains(pf)
                                );
                                boolean hasFiles = !allFiles.isEmpty(); // Re-introduce hasFiles

                                JMenuItem editAllRefsItem = new JMenuItem("Edit all References");
                                editAllRefsItem.addActionListener(e1 -> performContextActionAsync(ContextAction.EDIT, selectedFragments));
                                editAllRefsItem.setEnabled(allFilesAreTrackedProjectFiles);
                                if (!allFilesAreTrackedProjectFiles && !allFiles.isEmpty()) {
                                    editAllRefsItem.setToolTipText("Cannot edit because selection includes untracked or external files.");
                                } else if (allFiles.isEmpty()) {
                                    editAllRefsItem.setToolTipText("No files associated with the selection to edit.");
                                }

                                JMenuItem readAllRefsItem = new JMenuItem("Read all References");
                                readAllRefsItem.addActionListener(e1 -> performContextActionAsync(ContextAction.READ, selectedFragments));
                                readAllRefsItem.setEnabled(hasFiles); // Disable if no files associated

                                JMenuItem summarizeAllRefsItem = new JMenuItem("Summarize all References");
                                summarizeAllRefsItem.addActionListener(e1 -> performContextActionAsync(ContextAction.SUMMARIZE, selectedFragments));
                                summarizeAllRefsItem.setEnabled(hasFiles); // Disable if no files associated

                                contextMenu.add(editAllRefsItem);
                                contextMenu.add(readAllRefsItem);
                                contextMenu.add(summarizeAllRefsItem);
                            }
                        }

                        // Add Copy and Drop actions with a separator
                        contextMenu.addSeparator();
                        JMenuItem copySelectionItem = new JMenuItem("Copy");
                        copySelectionItem.addActionListener(ev -> {
                            var selectedFragments = getSelectedFragments();
                            performContextActionAsync(ContextAction.COPY, selectedFragments);
                        });
                        contextMenu.add(copySelectionItem);

                        JMenuItem dropSelectionItem = new JMenuItem("Drop");
                        dropSelectionItem.addActionListener(ev -> {
                            var selectedFragments = getSelectedFragments();
                            performContextActionAsync(ContextAction.DROP, selectedFragments);
                        });
                        contextMenu.add(dropSelectionItem);

                        dropSelectionItem.setEnabled(contextManager.selectedContext().equals(contextManager.topContext()));
                    } else {
                        // No row selected - show the popup with all options
                        tablePopupMenu.show(contextTable, e.getX(), e.getY());
                    }

                    // Final theme registration before showing
                    if (chrome.themeManager != null) {
                        chrome.themeManager.registerPopupMenu(contextMenu);
                    }
                    contextMenu.show(contextTable, e.getX(), e.getY());
                }
            }
        });

        // Set selection mode to allow multiple selection
        contextTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Install custom TransferHandler for copy operations
        contextTable.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                String contentToCopy = getSelectedContent(getSelectedFragments());
                if (!contentToCopy.isEmpty()) {
                    return new StringSelection(contentToCopy);
                }
                return null;
            }
        });

        // Add Ctrl+V paste handler for the table
        var pasteKeyStroke = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        var pasteActionKey = "pasteAction";
        contextTable.getInputMap(JComponent.WHEN_FOCUSED).put(pasteKeyStroke, pasteActionKey);
        contextTable.getActionMap().put(pasteActionKey, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                performContextActionAsync(ContextAction.PASTE, List.of());
            }
        });

        // Setup right-click popup menu for when no rows are selected
        tablePopupMenu = new JPopupMenu();

        // Add options submenu
        JMenu addMenu = new JMenu("Add");
        AddMenuFactory.populateAddMenu(addMenu, this);

        tablePopupMenu.add(addMenu);
        tablePopupMenu.addSeparator();

        JMenuItem dropAllMenuItem = new JMenuItem("Drop All");
        dropAllMenuItem.addActionListener(e -> performContextActionAsync(ContextAction.DROP, List.of()));
        tablePopupMenu.add(dropAllMenuItem);

        JMenuItem copyAllMenuItem = new JMenuItem("Copy All");
        copyAllMenuItem.addActionListener(e -> {
            performContextActionAsync(ContextAction.COPY, List.of());
        });
        tablePopupMenu.add(copyAllMenuItem);

        JMenuItem pasteMenuItem = new JMenuItem("Paste text, images, urls");
        pasteMenuItem.addActionListener(e -> {
            performContextActionAsync(ContextAction.PASTE, List.of());
        });
        tablePopupMenu.add(pasteMenuItem);

        // Register the popup menu with the theme manager
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(tablePopupMenu);
        } else {
            SwingUtilities.invokeLater(() -> {
                if (chrome.themeManager != null) {
                    chrome.themeManager.registerPopupMenu(tablePopupMenu);
                }
            });
        }

        // Build summary panel
        var contextSummaryPanel = new JPanel(new BorderLayout());
        locSummaryPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JLabel innerLabel = new JLabel(" ");
        innerLabel.setBorder(new EmptyBorder(5, 5, 5, 5));
        locSummaryPanel.add(innerLabel);
        locSummaryPanel.setBorder(BorderFactory.createEmptyBorder());
        contextSummaryPanel.add(locSummaryPanel, BorderLayout.NORTH);

        // Warning panel (for red/yellow context size warnings)
        warningPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        warningPanel.setBorder(new EmptyBorder(0, 5, 5, 5)); // Top, Left, Bottom, Right padding
        contextSummaryPanel.add(warningPanel, BorderLayout.CENTER);

        // Table panel
        var tablePanel = new JPanel(new BorderLayout());
        var tableScrollPane = new JScrollPane(contextTable, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        tableScrollPane.setPreferredSize(new Dimension(600, 150));

        BorderUtils.addFocusBorder(tableScrollPane, contextTable);

        // Add a mouse listener to the scroll pane for right-clicks on empty areas
        tableScrollPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleScrollPanePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handleScrollPanePopup(e);
            }

            private void handleScrollPanePopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    // Get the event point in view coordinates
                    Point viewPoint = SwingUtilities.convertPoint(tableScrollPane, e.getPoint(),
                                                                  tableScrollPane.getViewport().getView());

                    // If the click is in the table and on a row, let the table's listener handle it
                    if (contextTable.getRowCount() > 0) {
                        int row = contextTable.rowAtPoint(viewPoint);
                        if (row >= 0) {
                            return;
                        }
                    }

                    // Otherwise show the table popup menu
                    tablePopupMenu.show(tableScrollPane, e.getX(), e.getY());
                }
            }
        });

        tablePanel.add(tableScrollPane, BorderLayout.CENTER);

        setLayout(new BorderLayout());

        add(tablePanel, BorderLayout.CENTER);

        add(contextSummaryPanel, BorderLayout.SOUTH);

        // Listener for table scroll pane (focus on click, specific popup for empty area)
        tableScrollPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                // Retain existing specific behavior for scroll pane clicks (focus table)
                // Popup is handled by handleScrollPanePopup
                if (!e.isPopupTrigger() && SwingUtilities.isLeftMouseButton(e)) {
                    contextTable.requestFocusInWindow();
                }
            }
        });

        // Shared listener for panel-wide context menu and focus
        MouseAdapter panelPopupAndFocusListener = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!e.isPopupTrigger() && SwingUtilities.isLeftMouseButton(e)) {
                    contextTable.requestFocusInWindow();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    tablePopupMenu.show(e.getComponent(), e.getX(), e.getY());
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    tablePopupMenu.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        };

        // Add the listener to the panel itself
        this.addMouseListener(panelPopupAndFocusListener);
        // Add the listener to the summary panel and its sub-panels to ensure consistent popup behavior
        contextSummaryPanel.addMouseListener(panelPopupAndFocusListener);
        locSummaryPanel.addMouseListener(panelPopupAndFocusListener);
        warningPanel.addMouseListener(panelPopupAndFocusListener);
    }

    /**
     * Gets the list of selected fragments
     */
    public List<ContextFragment> getSelectedFragments() {
        return SwingUtil.runOnEdt(() -> {
            var fragments = new ArrayList<ContextFragment>();
            int[] selectedRows = contextTable.getSelectedRows();
            var tableModel = (DefaultTableModel) contextTable.getModel();
            for (int row : selectedRows) {
                fragments.add((ContextFragment) tableModel.getValueAt(row, FRAGMENT_COLUMN));
            }
            return fragments;
        }, List.of());
    }

    /**
     * Populates the context table from a Context object.
     */
    public void populateContextTable(Context ctx) {
        assert SwingUtilities.isEventDispatchThread() : "Not on EDT";
        this.currentContext = ctx;
        var tableModel = (DefaultTableModel) contextTable.getModel();
        tableModel.setRowCount(0);

        if (ctx == null || ctx.isEmpty()) {
            ((JLabel) locSummaryPanel.getComponent(0)).setText(EMPTY_CONTEXT);
            revalidate();
            repaint();
            return;
        }

        var allFragments = ctx.getAllFragmentsInDisplayOrder();
        int totalLines = 0;
        StringBuilder fullText = new StringBuilder();
        for (var frag : allFragments) {
            String locText;
            if (frag.isText() || frag.getType().isOutputFragment()) {
                var text = frag.text();
                fullText.append(text).append("\n");
                int loc = text.split("\\r?\\n", -1).length;
                totalLines += loc;
                locText = "%,d".formatted(loc);
            } else {
                locText = "Image";
            }
            var desc = frag.description();

            // Mark editable if it's in the editable streams
            boolean isEditable = ctx.editableFiles().anyMatch(e -> e == frag);
            if (isEditable) {
                desc = "✏️ " + desc;
            }

            // Build file references
            List<TableUtils.FileReferenceList.FileReferenceData> fileReferences = new ArrayList<>();
            if (frag.getType() != ContextFragment.FragmentType.PROJECT_PATH) {
                fileReferences = frag.files()
                        .stream()
                        .map(file -> new TableUtils.FileReferenceList.FileReferenceData(file.getFileName(), file.toString(), file))
                        .distinct()
                        .sorted(Comparator.comparing(TableUtils.FileReferenceList.FileReferenceData::getFileName))
                        .collect(Collectors.toList());
            }

            tableModel.addRow(new Object[]{locText, desc, fileReferences, frag});
        }

        var approxTokens = Messages.getApproximateTokens(fullText.toString());
        var innerLabel = (JLabel) locSummaryPanel.getComponent(0);

        // Check for context size warnings against configured models
        var models = contextManager.getService();
        var project = contextManager.getProject();

        Map<String, Integer> redWarningModels = new HashMap<>();
        Map<String, Integer> yellowWarningModels = new HashMap<>();

        List<Service.ModelConfig> configuredModelChecks = List.of(
                project.getArchitectModelConfig(),
                project.getCodeModelConfig(),
                project.getAskModelConfig(),
                project.getSearchModelConfig()
        );

        for (var config : configuredModelChecks) {
            if (config.name() == null || config.name().isBlank()) {
                continue;
            }
            try {
                var model = models.getModel(config.name(), config.reasoning());
                // Skip if model is unavailable or a placeholder
                if (model instanceof Service.UnavailableStreamingModel) {
                    logger.debug("Skipping unavailable model for context warning: {}", config.name());
                    continue;
                }

                int maxInputTokens = models.getMaxInputTokens(model);
                if (maxInputTokens <= 0) {
                    logger.warn("Model {} has invalid maxInputTokens: {}. Skipping for context warning.", config.name(), maxInputTokens);
                    continue;
                }

                // Red warning: context > 90.9% of max (approxTokens > maxInputTokens / 1.1)
                if (approxTokens > maxInputTokens / 1.1) {
                    redWarningModels.put(config.name(), maxInputTokens);
                }
                // Yellow warning: context > 50% of max (approxTokens > maxInputTokens / 2.0)
                else if (approxTokens > maxInputTokens / 2.0) {
                    yellowWarningModels.put(config.name(), maxInputTokens);
                }
            } catch (Exception e) {
                logger.warn("Error processing model {} for context warning: {}", config.name(), e.getMessage(), e);
            }
        }

        String warningTooltip = """
        Consider replacing full files with summaries or tackling a smaller piece of your problem to start with.
        Deep Scan can help surface the parts of your codebase that are necessary to solving the problem.
        """;

        // Always set the standard summary text on innerLabel
        innerLabel.setForeground(UIManager.getColor("Label.foreground")); // Reset to default color
        var costEstimates = calculateCostEstimates(approxTokens, models);
        String costText = costEstimates.isEmpty() ? "" : " Estimated cost/request is " + String.join(", ", costEstimates);
        innerLabel.setText("Total: %,d LOC, or about %,dk tokens.%s".formatted(totalLines, approxTokens / 1000, costText));
        innerLabel.setToolTipText(null); // Clear tooltip

        // Remove any existing warning labels from the warningPanel
        warningPanel.removeAll();

        if (!redWarningModels.isEmpty()) {
            String modelListStr = redWarningModels.entrySet().stream()
                    .map(entry -> String.format("%s (%,d)", entry.getKey(), entry.getValue()))
                    .collect(Collectors.joining(", "));
            String warningText = String.format("Warning! Your Workspace (~%,d tokens) fills more than 90%% of the context window for the following models: %s. Performance will be degraded.", approxTokens, modelListStr);
            JLabel warningLabel = new JLabel(warningText);
            warningLabel.setForeground(Color.RED);
            warningLabel.setToolTipText(warningTooltip);
            warningPanel.add(warningLabel); 
        } else if (!yellowWarningModels.isEmpty()) {
            String modelListStr = yellowWarningModels.entrySet().stream()
                    .map(entry -> String.format("%s (%,d)", entry.getKey(), entry.getValue()))
                    .collect(Collectors.joining(", "));
            String warningText = String.format("Warning! Your Workspace (~%,d tokens) fills more than half of the context window for the following models: %s. Performance may be degraded.", approxTokens, modelListStr);
            JLabel warningLabel = new JLabel(warningText);
            warningLabel.setForeground(Color.YELLOW); // Standard yellow might be hard to see on some themes; consider a darker yellow or orange if needed.
            warningLabel.setToolTipText(warningTooltip);
            warningPanel.add(warningLabel); 
        }
        
        warningPanel.revalidate();
        warningPanel.repaint();

        revalidate();
        repaint();
    }

    /**
     * Called by Chrome to refresh the table if context changes
     */
    public void updateContextTable() {
        SwingUtilities.invokeLater(() -> populateContextTable(contextManager.selectedContext()));
    }

    /**
     * Build "Add file" menu item for a single file reference
     */
    private JMenuItem buildAddMenuItem(TableUtils.FileReferenceList.FileReferenceData fileRef) {
        JMenuItem editItem = new JMenuItem("Edit " + fileRef.getFullPath());
        editItem.addActionListener(e -> {
            if (fileRef.getRepoFile() != null) {
                performContextActionAsync(
                        ContextAction.EDIT,
                        List.of(new ContextFragment.ProjectPathFragment(fileRef.getRepoFile(), contextManager)) // Pass contextManager
                );
            } else {
                chrome.toolErrorRaw("Cannot edit file: " + fileRef.getFullPath() + " - no ProjectFile available");
            }
        });
        // Disable if no git, file isn't a ProjectFile, or file isn't tracked
        var project = contextManager.getProject();
        var repoFile = fileRef.getRepoFile(); // Cache the result
        if (project == null || !project.hasGit()) {
            editItem.setEnabled(false);
            editItem.setToolTipText("Editing not available without Git");
        } else if (repoFile == null) {
            editItem.setEnabled(false);
            editItem.setToolTipText("Editing not available for external files"); // Should generally not happen if repoFile is null
        } else if (!project.getRepo().getTrackedFiles().contains(repoFile)) {
            editItem.setEnabled(false);
            editItem.setToolTipText("Cannot edit untracked file: " + fileRef.getFullPath());
        }
        return editItem;
    }

    /**
     * Build "Read file" menu item for a single file reference
     */
    private JMenuItem buildReadMenuItem(TableUtils.FileReferenceList.FileReferenceData fileRef) {
        JMenuItem readItem = new JMenuItem("Read " + fileRef.getFullPath());
        readItem.addActionListener(e -> {
            if (fileRef.getRepoFile() != null) {
                performContextActionAsync(
                        ContextAction.READ,
                        List.of(new ContextFragment.ProjectPathFragment(fileRef.getRepoFile(), contextManager)) // Pass contextManager
                );
            } else {
                chrome.toolErrorRaw("Cannot read file: " + fileRef.getFullPath() + " - no ProjectFile available");
            }
        });
        return readItem;
    }

    /**
     * Shows a preview of the fragment contents
     */
    private void showFragmentPreview(ContextFragment fragment) {
        chrome.openFragmentPreview(fragment);
    }

    /**
     * Build "Summarize file" menu item for a single file reference
     */
    private JMenuItem buildSummarizeMenuItem(TableUtils.FileReferenceList.FileReferenceData fileRef) {
        JMenuItem summarizeItem = new JMenuItem("Summarize " + fileRef.getFullPath());
        summarizeItem.addActionListener(e -> {
            if (fileRef.getRepoFile() != null) {
                performContextActionAsync(
                        ContextAction.SUMMARIZE,
                        List.of(new ContextFragment.ProjectPathFragment(fileRef.getRepoFile(), contextManager)) // Pass contextManager
                );
            } else {
                chrome.toolErrorRaw("Cannot summarize: " + fileRef.getFullPath() + " - ProjectFile information not available");
            }
        });
        return summarizeItem;
    }

    // ------------------------------------------------------------------
    // Context Action Logic
    // ------------------------------------------------------------------

    // Public getter for ContextManager needed by AddMenuFactory
    public ContextManager getContextManager() {
        return contextManager;
    }

    /**
     * Checks if analyzer is ready for operations, shows error message if not.
     */
    private boolean isAnalyzerReady() {
        var analyzer = contextManager.getAnalyzerWrapper().getNonBlocking();
        if (analyzer == null) {
            chrome.systemOutput("Code Intelligence is still being built. Please wait until completion.");
            return false;
        }
        return true;
    }

    /**
     * Shows the symbol selection dialog and adds usage information for the selected symbol.
     */
    public void findSymbolUsageAsync() {
        if (!isAnalyzerReady()) {
            return;
        }

        contextManager.submitContextTask("Find Symbol Usage", () -> {
            try {
                var analyzer = contextManager.getAnalyzerUninterrupted();
                if (analyzer.isEmpty()) {
                    chrome.toolErrorRaw("Code Intelligence is empty; nothing to add");
                    return;
                }

                String symbol = showSymbolSelectionDialog("Select Symbol", CodeUnitType.ALL);
                if (symbol != null && !symbol.isBlank()) {
                    contextManager.usageForIdentifier(symbol);
                } else {
                    chrome.systemOutput("No symbol selected.");
                }
            } catch (CancellationException cex) {
                chrome.systemOutput("Symbol selection canceled.");
            }
            // No finally needed, submitContextTask handles enabling buttons
        });
    }

    /**
     * Shows the method selection dialog and adds callers information for the selected method.
     */
    public void findMethodCallersAsync() {
        if (!isAnalyzerReady()) {
            return;
        }

        contextManager.submitContextTask("Find Method Callers", () -> {
            try {
                var analyzer = contextManager.getAnalyzerUninterrupted();
                if (analyzer.isEmpty()) {
                    chrome.toolErrorRaw("Code Intelligence is empty; nothing to add");
                    return;
                }

                var dialog = showCallGraphDialog("Select Method", true);
                if (dialog == null || !dialog.isConfirmed()) { // Check confirmed state
                    chrome.systemOutput("No method selected.");
                } else {

                    contextManager.addCallersForMethod(dialog.getSelectedMethod(), dialog.getDepth(), dialog.getCallGraph());
                }
            } catch (CancellationException cex) {
                chrome.systemOutput("Method selection canceled.");
            }
            // No finally needed, submitContextTask handles enabling buttons
        });
    }

    /**
     * Shows the call graph dialog and adds callees information for the selected method.
     */
    public void findMethodCalleesAsync() {
        if (!isAnalyzerReady()) {
            return;
        }

        contextManager.submitContextTask("Find Method Callees", () -> {
            try {
                var analyzer = contextManager.getAnalyzerUninterrupted();
                if (analyzer.isEmpty()) {
                    chrome.toolErrorRaw("Code Intelligence is empty; nothing to add");
                    return;
                }

                var dialog = showCallGraphDialog("Select Method for Callees", false);
                if (dialog == null || !dialog.isConfirmed() || dialog.getSelectedMethod() == null || dialog.getSelectedMethod().isBlank()) {
                    chrome.systemOutput("No method selected.");
                } else {

                    contextManager.calleesForMethod(dialog.getSelectedMethod(), dialog.getDepth(), dialog.getCallGraph());
                }
            } catch (CancellationException cex) {
                chrome.systemOutput("Method selection canceled.");
            }
            // No finally needed, submitContextTask handles enabling buttons
        });
    }

    /**
     * Show the symbol selection dialog with a type filter
     */
    private String showSymbolSelectionDialog(String title, Set<CodeUnitType> typeFilter) {
        var analyzer = contextManager.getAnalyzerUninterrupted();
        var dialogRef = new AtomicReference<SymbolSelectionDialog>();
        SwingUtil.runOnEdt(() -> {
            var dialog = new SymbolSelectionDialog(chrome.getFrame(), analyzer, title, typeFilter);
            dialog.setSize((int) (chrome.getFrame().getWidth() * 0.9), dialog.getHeight());
            dialog.setLocationRelativeTo(chrome.getFrame());
            dialog.setVisible(true);
            dialogRef.set(dialog);
        });
        try {
            var dialog = dialogRef.get();
            if (dialog != null && dialog.isConfirmed()) {
                return dialog.getSelectedSymbol();
            }
            return null;
        } finally {
            chrome.focusInput();
        }
    }

    /**
     * Show the call graph dialog for configuring method and depth
     */
    private CallGraphDialog showCallGraphDialog(String title, boolean isCallerGraph) {
        var analyzer = contextManager.getAnalyzerUninterrupted();
        var dialogRef = new AtomicReference<CallGraphDialog>();
        SwingUtil.runOnEdt(() -> {
            var dialog = new CallGraphDialog(chrome.getFrame(), analyzer, title, isCallerGraph);
            dialog.setSize((int) (chrome.getFrame().getWidth() * 0.9), dialog.getHeight());
            dialog.setLocationRelativeTo(chrome.getFrame());
            dialog.setVisible(true);
            dialogRef.set(dialog);
        });
        try {
            var dialog = dialogRef.get();
            if (dialog != null && dialog.isConfirmed()) {
                return dialog;
            }
            return null;
        } finally {
            chrome.focusInput();
        }
    }

    /**
     * Performed by the action buttons/menus in the context panel: "edit / read / copy / drop / summarize / paste"
     * If selectedFragments is empty, it means "All". We handle logic accordingly.
     */
    public Future<?> performContextActionAsync(ContextAction action, List<? extends ContextFragment> selectedFragments) // Use wildcard
    {
        // Use submitContextTask from ContextManager to run the action on the appropriate executor
        return contextManager.submitContextTask(action + " action", () -> { 
            try {
                switch (action) {
                    case EDIT -> doEditAction(selectedFragments);
                    case READ -> doReadAction(selectedFragments);
                    case COPY -> doCopyAction(selectedFragments);
                    case DROP -> doDropAction(selectedFragments);
                    case SUMMARIZE -> doSummarizeAction(selectedFragments);
                    case PASTE -> doPasteAction();
                }
            } catch (CancellationException cex) {
                chrome.systemOutput(action + " canceled."); 
            }
            // No finally block needed here as submitContextTask handles enabling buttons
        });
    }


    /** Edit Action: Only allows selecting Project Files */
    private void doEditAction(List<? extends ContextFragment> selectedFragments) { // Use wildcard
        var project = contextManager.getProject(); 
        if (selectedFragments.isEmpty()) {
            // Show dialog allowing ONLY file selection (no external)
            var selection = showMultiSourceSelectionDialog("Edit Files",
                                                           false, // No external files for edit
                                                           CompletableFuture.completedFuture(project.getRepo().getTrackedFiles()), // Only tracked files
                                                           Set.of(SelectionMode.FILES)); // Only FILES mode

            if (selection != null && selection.files() != null && !selection.files().isEmpty()) {
                // We disallowed external files, so this cast should be safe
                var projectFiles = toProjectFilesUnsafe(selection.files());
                contextManager.editFiles(projectFiles); 
            }
        } else {
            // Edit files from selected fragments
            var files = new HashSet<ProjectFile>();
            selectedFragments.stream()
                             .flatMap(fragment -> fragment.files().stream()) // Corrected: No analyzer
                             .filter(Objects::nonNull)
                             .forEach(files::add);
            contextManager.editFiles(files); 
        }
    }

    /** Read Action: Allows selecting Files (internal/external) */
    private void doReadAction(List<? extends ContextFragment> selectedFragments) { // Use wildcard
        var project = contextManager.getProject();
        if (selectedFragments.isEmpty()) {
            // Show dialog allowing ONLY file selection (internal + external)
            // TODO when we can extract a single class from a source file, enable classes as well
            var selection = showMultiSourceSelectionDialog("Add Read-Only Context",
                                                           true, // Allow external files
                                                           CompletableFuture.completedFuture(project.getAllFiles()), // All project files for completion
                                                           Set.of(SelectionMode.FILES)); // FILES mode only

            if (selection == null || selection.files() == null || selection.files().isEmpty()) {
                return;
            }

            contextManager.addReadOnlyFiles(selection.files());
        } else {
            // Add files from selected fragments
            var files = new HashSet<BrokkFile>();
            for (var fragment : selectedFragments) {
                files.addAll(fragment.files()); // No analyzer
            }
            contextManager.addReadOnlyFiles(files);
        }
    }

    private void doCopyAction(List<? extends ContextFragment> selectedFragments) {
        var content = getSelectedContent(selectedFragments);
        var sel = new java.awt.datatransfer.StringSelection(content);
        var cb = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
        cb.setContents(sel, sel);
        chrome.systemOutput("Content copied to clipboard"); 
    }

    private @NotNull String getSelectedContent(List<? extends ContextFragment> selectedFragments) {
        String content;
        if (selectedFragments.isEmpty()) {
            // gather entire context
            List<ChatMessage> msgs;
            try {
                msgs = CopyExternalPrompts.instance.collectMessages(contextManager);
            } catch (InterruptedException e) {
                // the signature is misleading, IE will never be thrown on this path
                throw new AssertionError(e);
            }
            var combined = new StringBuilder();
            for (var m : msgs) {
                if (!(m instanceof AiMessage)) {
                    combined.append(Messages.getText(m)).append("\n\n");
                }
            }

            // Get instructions from context
            combined.append("\n<goal>\n").append(chrome.getInputText()).append("\n</goal>");
            content = combined.toString();
        } else {
            // copy only selected fragments
            var sb = new StringBuilder();
            for (var frag : selectedFragments) {
                sb.append(frag.text()).append("\n\n"); // No analyzer
            }
            content = sb.toString();
        }
        return content;
    }

    private void doPasteAction() {
        var clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
        var contents = clipboard.getContents(null);
        if (contents == null) {
            chrome.toolErrorRaw("Clipboard is empty or unavailable");
            return;
        }

        // Log all available flavors for debugging
        var flavors = contents.getTransferDataFlavors();
        logger.debug("Clipboard flavors available: {}", java.util.Arrays.stream(flavors)
                .map(DataFlavor::getMimeType)
                .collect(Collectors.joining(", ")));

        // Prioritize Image Flavors - check all available flavors for image compatibility
        for (var flavor : flavors) {
            try {
                // Check if it's the standard image flavor or has a MIME type indicating an image
                if (flavor.isFlavorJavaFileListType() || flavor.getMimeType().startsWith("image/")) {
                    logger.debug("Attempting to process flavor: {}", flavor.getMimeType());
                    Object data = contents.getTransferData(flavor);
                    java.awt.Image image = null;

                    switch (data) {
                        case Image image1 -> image = image1;
                        case java.io.InputStream inputStream ->
                            // Try to read the stream as an image using ImageIO
                                image = javax.imageio.ImageIO.read(inputStream);
                        case List<?> fileList when !fileList.isEmpty() -> {
                            // Handle file list (e.g., dragged image file from file manager)
                            var file = fileList.getFirst();
                            if (file instanceof java.io.File f && f.getName().matches("(?i).*(png|jpg|jpeg|gif|bmp)$")) {
                                image = javax.imageio.ImageIO.read(f);
                            }
                        }
                        default -> {
                        }
                    }

                    if (image != null) {
                            contextManager.addPastedImageFragment(image);
                            chrome.systemOutput("Pasted image added to context");
                            return;
                        }
                    }
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("INCR")) {
                        chrome.toolErrorRaw("Unable to paste image data from Windows to Brokk running under WSL. This is a limitation of WSL. You can write the image to a file and read it that way instead.");
                        return;
                    }
                    logger.error("Failed to process image flavor: {}", flavor.getMimeType(), e);
                }
            }

        // Text Flavor
        if (contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
            String clipboardText;
            try {
                clipboardText = (String) contents.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
                if (clipboardText.isBlank()) {
                    chrome.toolErrorRaw("Clipboard text is empty");
                    return;
                }
            } catch (Exception e) {
                chrome.toolErrorRaw("Failed to read clipboard text: " + e.getMessage());
                return;
            }

            // Process the clipboard text
            clipboardText = clipboardText.trim();
            String content = clipboardText;
            boolean wasUrl = false;

            if (isUrl(clipboardText)) {
                URI uri;
                try {
                    uri = new URI(clipboardText);
                } catch (URISyntaxException e) {
                    logger.warn("Thought we had a url but we did not: " + clipboardText);
                    // Not a valid URI, proceed to treat as plain text
                    uri = null;
                }

                if (uri != null) { // Only proceed if URI parsing was successful
                    // Try to handle as image URL first
                    if (isImageUri(uri)) {
                        try {
                            chrome.systemOutput("Fetching image from " + clipboardText);
                            java.awt.Image image = javax.imageio.ImageIO.read(uri.toURL());
                            if (image != null) {
                                contextManager.addPastedImageFragment(image);
                                chrome.systemOutput("Pasted image from URL added to context");
                                chrome.actionComplete();
                                return; // Image handled, done with paste action
                            } else {
                                logger.warn("URL {} identified as image, but ImageIO.read returned null. Falling back to text.", clipboardText);
                                chrome.systemOutput("Could not load image from URL. Trying to fetch as text.");
                            }
                        } catch (IOException e) {
                            logger.warn("Failed to fetch or decode image from URL {}: {}. Falling back to text.", clipboardText, e.getMessage());
                            chrome.systemOutput("Failed to load image from URL: " + e.getMessage() + ". Trying to fetch as text.");
                            // Fall through to fetching as text
                        }
                    }

                    // Fallback: If not an image URL or image fetching failed, try to fetch as text
                    try {
                        chrome.systemOutput("Fetching content from " + clipboardText);
                        content = WorkspaceTools.fetchUrlContent(uri);
                        content = HtmlToMarkdown.maybeConvertToMarkdown(content);
                        wasUrl = true;
                        chrome.actionComplete();
                    } catch (IOException e) {
                        chrome.toolErrorRaw("Failed to fetch or process URL content as text: " + e.getMessage());
                        content = clipboardText; // Revert to original clipboard text if fetch fails
                    }
                }
            }

            // Try to parse as stacktrace
            var stacktrace = StackTrace.parse(content);
            if (stacktrace != null && contextManager.addStacktraceFragment(stacktrace)) {
                return;
            }

            // Add as string fragment (possibly converted from HTML)
            Future<String> summaryFuture = contextManager.submitSummarizePastedText(content);
            String finalContent = content;
            contextManager.pushContext(ctx -> {
                var fragment = new ContextFragment.PasteTextFragment(contextManager, finalContent, summaryFuture); // Pass contextManager
                return ctx.addVirtualFragment(fragment);
            });

            // Inform the user about what happened
            if (stacktrace == null) {
                // addStackTraceFragment sends its own messages
                String message = wasUrl ? "URL content fetched and added" : "Clipboard content added as text";
                chrome.systemOutput(message);
            }
        } else {
            chrome.toolErrorRaw("Unsupported clipboard content type");
        }
    }

    private void doDropAction(List<? extends ContextFragment> selectedFragments) // Use wildcard
    {
        if (selectedFragments.isEmpty()) {
            if (contextManager.topContext().isEmpty()) { 
                chrome.toolErrorRaw("No context to drop"); 
                return;
            }
            contextManager.dropAll(); 
        } else {
            var idsToRemove = new ArrayList<Integer>();
            boolean clearHistory = false;

            for (var frag : selectedFragments) {
                if (frag.getType() == ContextFragment.FragmentType.HISTORY) {
                    clearHistory = true;
                } else {
                    idsToRemove.add(frag.id());
                }
            }

            if (clearHistory) {
                contextManager.clearHistory();
                chrome.systemOutput("Cleared task history");
            }

            if (!idsToRemove.isEmpty()) {
                contextManager.drop(idsToRemove); // Use the new ID-based method
                chrome.systemOutput("Dropped " + idsToRemove.size() + " item(s)");
            }
        }
    }

    private void doSummarizeAction(List<? extends ContextFragment> selectedFragments) {
        var project = contextManager.getProject();
        if (!isAnalyzerReady()) {
            return;
        }

        HashSet<ProjectFile> selectedFiles = new HashSet<>();
        HashSet<CodeUnit> selectedClasses = new HashSet<>();

        if (selectedFragments.isEmpty()) {
            // Dialog case: select files OR classes
            // Prepare project files for completion (can be done async)
            // No need to filter here anymore, the dialog handles presentation.
            var completableProjectFiles = contextManager.submitBackgroundTask("Gathering project files", project::getAllFiles);

            // Show dialog allowing selection of files OR classes
            var selection = showMultiSourceSelectionDialog("Summarize Sources",
                                                           false, // No external files for summarize
                                                           completableProjectFiles, // All project files for completion
                                                           Set.of(SelectionMode.FILES, SelectionMode.CLASSES)); // Both modes allowed

            if (selection == null || selection.isEmpty()) {
                chrome.systemOutput("No files or classes selected for summarization.");
                return;
            }

            // Add selected files (must be ProjectFile for summarization)
            if (selection.files() != null) {
                selectedFiles.addAll(toProjectFilesUnsafe(selection.files()));
            }
            // Add selected classes/symbols
            if (selection.classes() != null) {
                selectedClasses.addAll(selection.classes());
            }
        } else {
            // Fragment case: Extract files and classes from selected fragments
            for (var frag : selectedFragments) {
                selectedFiles.addAll(frag.files());
            }
        }

        if (selectedFiles.isEmpty() && selectedClasses.isEmpty()) {
            chrome.toolErrorRaw("No files or classes identified for summarization in the selection.");
            return;
        }

        // Call the updated addSummaries method, which outputs a message on success
        boolean success = contextManager.addSummaries(selectedFiles, selectedClasses);
        if (!success) {
            chrome.toolErrorRaw("No summarizable content found in the selected files or symbols.");
        }
    }

    /**
     * Cast BrokkFile to ProjectFile. Will throw if ExternalFiles are present.
     * Use with caution, only when external files are disallowed or handled separately.
     */
    private List<ProjectFile> toProjectFilesUnsafe(List<BrokkFile> files) {
        if (files == null) return List.of();
        return files.stream().map(f -> {
            if (f instanceof ProjectFile pf) {
                return pf;
            }
            throw new ClassCastException("Expected only ProjectFile but got " + f.getClass().getName());
        }).toList();
    }

    /**
     * Show the multi-source selection dialog with configurable modes.
     * This is called by the do*Action methods within this panel.
     *
     * @param title              Dialog title.
     * @param allowExternalFiles Allow selection of external files in the Files tab.
     * @param projectCompletionsFuture        Set of completable project files.
     * @param modes              Set of selection modes (FILES, CLASSES) to enable.
     * @return The Selection record containing lists of files and/or classes, or null if cancelled.
     */
    private MultiFileSelectionDialog.Selection showMultiSourceSelectionDialog(String title, boolean allowExternalFiles, Future<Set<ProjectFile>> projectCompletionsFuture, Set<SelectionMode> modes) {
        var dialogRef = new AtomicReference<MultiFileSelectionDialog>();
        SwingUtil.runOnEdt(() -> {
            var dialog = new MultiFileSelectionDialog(chrome.getFrame(), contextManager, title, allowExternalFiles, projectCompletionsFuture, modes);
            // Use dialog's preferred size after packing, potentially adjust width
            dialog.setSize(Math.max(600, dialog.getWidth()), Math.max(550, dialog.getHeight()));
            dialog.setLocationRelativeTo(chrome.getFrame());
            dialog.setVisible(true);
            dialogRef.set(dialog);
        });
        try {
            var dialog = dialogRef.get();
            if (dialog != null && dialog.isConfirmed()) {
                return dialog.getSelection(); // Return the Selection record
            }
            return null; // Indicate cancellation or no selection
        } finally {
            chrome.focusInput();
        }
    }

    private boolean isUrl(String text) {
        return text.matches("^https?://\\S+$");
    }

    private boolean isImageUri(URI uri) {
        Request request = new Request.Builder()
                .url(uri.toString())
                .head() // Send a HEAD request
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                String contentType = response.header("Content-Type");
                if (contentType != null) {
                    logger.debug("URL {} Content-Type: {}", uri, contentType);
                    return contentType.toLowerCase().startsWith("image/");
                } else {
                    logger.warn("URL {} did not return a Content-Type header.", uri);
                }
            } else {
                logger.warn("HEAD request to {} failed with code: {}", uri, response.code());
            }
        } catch (IOException e) {
            logger.error("IOException during HEAD request to {}: {}", uri, e.getMessage());
        }
        return false;
    }

    /**
     * Calculate cost estimates for the configured models.
     */
    private List<String> calculateCostEstimates(int inputTokens, Service models) {
        var costEstimates = new ArrayList<String>();
        var seenModels = new HashSet<String>();
        
        var project = contextManager.getProject();
        
        // Get the three configured models in order: code, ask, architect
        List<Service.ModelConfig> configsToCheck = List.of(
                project.getCodeModelConfig(), 
                project.getAskModelConfig(),
                project.getArchitectModelConfig()
        );
        
        for (var config : configsToCheck) {
            if (config.name() == null || config.name().isBlank() || seenModels.contains(config.name())) {
                continue; // Skip if model name is empty or already processed
            }
            
            try {
                var model = models.getModel(config.name(), config.reasoning());
                if (model instanceof Service.UnavailableStreamingModel) {
                    continue; // Skip unavailable models
                }
                
                var pricing = models.getModelPricing(config.name());
                if (pricing.bands().isEmpty()) {
                    continue; // Skip if no pricing info available
                }
                
                // Calculate estimated cost: input tokens * input price + min(4k, input/2) * output price
                long estimatedOutputTokens = Math.min(4000, inputTokens / 2);
                if (models.isReasoning(config)) {
                    estimatedOutputTokens += 1000;
                }
                double estimatedCost = pricing.estimateCost(inputTokens, 0, estimatedOutputTokens);
                
                costEstimates.add(String.format("%s: $%.2f", config.name(), estimatedCost));
                seenModels.add(config.name());
                
            } catch (Exception e) {
                logger.debug("Error calculating cost estimate for model {}: {}", config.name(), e.getMessage());
                // Continue to next model on error
            }
        }
        
        return costEstimates;
    }

    /**
     * Sets the editable state of the workspace panel.
     *
     * @param editable true to make the workspace editable, false otherwise.
     */
    public void setWorkspaceEditable(boolean editable) {
        SwingUtilities.invokeLater(() -> {
            this.workspaceCurrentlyEditable = editable;
            if (contextTable != null) {
                contextTable.repaint();
            }
        });
    }
}
