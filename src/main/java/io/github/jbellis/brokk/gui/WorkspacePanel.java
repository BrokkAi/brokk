package io.github.jbellis.brokk.gui;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.AnalyzerWrapper;
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
import io.github.jbellis.brokk.util.ImageUtil;
import io.github.jbellis.brokk.util.Messages;
import io.github.jbellis.brokk.util.StackTrace;
import okhttp3.OkHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.Arrays;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
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

    /**
     * Sealed interface representing different popup menu scenarios
     */
    public sealed interface PopupScenario permits NoSelection, FileBadge, SingleFragment, MultiFragment {
        List<Action> getActions(WorkspacePanel panel);
    }

    public static final class NoSelection implements PopupScenario {
        @Override
        public List<Action> getActions(WorkspacePanel panel) {
            return List.of(
                WorkspaceAction.DROP_ALL.createAction(panel),
                WorkspaceAction.COPY_ALL.createAction(panel),
                WorkspaceAction.PASTE.createAction(panel)
            );
        }
    }

    public static final class FileBadge implements PopupScenario {
        private final TableUtils.FileReferenceList.FileReferenceData fileRef;

        public FileBadge(TableUtils.FileReferenceList.FileReferenceData fileRef) {
            this.fileRef = fileRef;
        }

        @Override
        public List<Action> getActions(WorkspacePanel panel) {
            var actions = new ArrayList<Action>();
            
            if (fileRef.getRepoFile() != null) {
                actions.add(WorkspaceAction.SHOW_IN_PROJECT.createFileAction(panel, fileRef.getRepoFile()));
                actions.add(WorkspaceAction.VIEW_FILE.createFileAction(panel, fileRef.getRepoFile()));
                
                if (panel.hasGit()) {
                    actions.add(WorkspaceAction.VIEW_HISTORY.createFileAction(panel, fileRef.getRepoFile()));
                } else {
                    actions.add(WorkspaceAction.VIEW_HISTORY.createDisabledAction("Git not available for this project."));
                }
                
                actions.add(null); // Separator
                actions.add(WorkspaceAction.EDIT_FILE.createFileRefAction(panel, fileRef));
                actions.add(WorkspaceAction.READ_FILE.createFileRefAction(panel, fileRef));
                actions.add(WorkspaceAction.SUMMARIZE_FILE.createFileRefAction(panel, fileRef));
            }
            
            return actions;
        }
    }

    public static final class SingleFragment implements PopupScenario {
        private final ContextFragment fragment;

        public SingleFragment(ContextFragment fragment) {
            this.fragment = fragment;
        }

        @Override
        public List<Action> getActions(WorkspacePanel panel) {
            var actions = new ArrayList<Action>();
            
            // Show in Project (for PROJECT_PATH fragments)
            if (fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH) {
                fragment.files().stream()
                    .findFirst()
                    .ifPresent(projectFile -> actions.add(WorkspaceAction.SHOW_IN_PROJECT.createFileAction(panel, projectFile)));
            }
            
            // View File/Contents
            if (fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH) {
                actions.add(WorkspaceAction.VIEW_FILE.createFragmentAction(panel, fragment));
            } else {
                actions.add(WorkspaceAction.SHOW_CONTENTS.createFragmentAction(panel, fragment));
            }
            
            // View History/Compress History
            if (panel.hasGit() && fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH) {
                fragment.files().stream()
                    .findFirst()
                    .ifPresent(projectFile -> actions.add(WorkspaceAction.VIEW_HISTORY.createFileAction(panel, projectFile)));
            } else if (fragment.getType() == ContextFragment.FragmentType.HISTORY) {
                var cf = (ContextFragment.HistoryFragment) fragment;
                var uncompressedExists = cf.entries().stream().anyMatch(entry -> !entry.isCompressed());
                if (uncompressedExists) {
                    actions.add(WorkspaceAction.COMPRESS_HISTORY.createAction(panel));
                } else {
                    actions.add(WorkspaceAction.COMPRESS_HISTORY.createDisabledAction("No uncompressed history to compress"));
                }
            } else {
                actions.add(WorkspaceAction.VIEW_HISTORY.createDisabledAction("View History is available only for single project files."));
            }
            
            actions.add(null); // Separator
            
            // Edit/Read/Summarize
            if (fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH) {
                fragment.files().stream()
                    .findFirst()
                    .ifPresent(projectFile -> {
                        var fileData = new TableUtils.FileReferenceList.FileReferenceData(
                            projectFile.getFileName(),
                            projectFile.toString(),
                            projectFile
                        );
                        actions.add(WorkspaceAction.EDIT_FILE.createFileRefAction(panel, fileData));
                        actions.add(WorkspaceAction.READ_FILE.createFileRefAction(panel, fileData));
                        actions.add(WorkspaceAction.SUMMARIZE_FILE.createFileRefAction(panel, fileData));
                    });
            } else {
                var selectedFragments = List.of(fragment);
                actions.add(WorkspaceAction.EDIT_ALL_REFS.createFragmentsAction(panel, selectedFragments));
                actions.add(WorkspaceAction.READ_ALL_REFS.createFragmentsAction(panel, selectedFragments));
                actions.add(WorkspaceAction.SUMMARIZE_ALL_REFS.createFragmentsAction(panel, selectedFragments));
            }
            
            actions.add(null); // Separator
            actions.add(WorkspaceAction.COPY.createFragmentsAction(panel, List.of(fragment)));
            
            var dropAction = WorkspaceAction.DROP.createFragmentsAction(panel, List.of(fragment));
            if (!panel.workspaceCurrentlyEditable) {
                dropAction.setEnabled(false);
                dropAction.putValue(Action.SHORT_DESCRIPTION, READ_ONLY_TIP);
            }
            actions.add(dropAction);
            
            return actions;
        }
    }

    public static final class MultiFragment implements PopupScenario {
        private final List<ContextFragment> fragments;

        public MultiFragment(List<ContextFragment> fragments) {
            this.fragments = fragments;
        }

        @Override
        public List<Action> getActions(WorkspacePanel panel) {
            var actions = new ArrayList<Action>();
            
            actions.add(WorkspaceAction.SHOW_CONTENTS.createDisabledAction("Cannot view contents of multiple items at once."));
            actions.add(WorkspaceAction.VIEW_HISTORY.createDisabledAction("Cannot view history for multiple items."));
            
            actions.add(null); // Separator
            
            actions.add(WorkspaceAction.EDIT_ALL_REFS.createFragmentsAction(panel, fragments));
            actions.add(WorkspaceAction.READ_ALL_REFS.createFragmentsAction(panel, fragments));
            actions.add(WorkspaceAction.SUMMARIZE_ALL_REFS.createFragmentsAction(panel, fragments));
            
            actions.add(null); // Separator
            actions.add(WorkspaceAction.COPY.createFragmentsAction(panel, fragments));
            
            var dropAction = WorkspaceAction.DROP.createFragmentsAction(panel, fragments);
            if (!panel.workspaceCurrentlyEditable) {
                dropAction.setEnabled(false);
                dropAction.putValue(Action.SHORT_DESCRIPTION, READ_ONLY_TIP);
            }
            actions.add(dropAction);
            
            return actions;
        }
    }

    /**
     * Enum of reusable workspace actions
     */
    public enum WorkspaceAction {
        SHOW_IN_PROJECT("Show in Project"),
        VIEW_FILE("View File"),
        SHOW_CONTENTS("Show Contents"),
        VIEW_HISTORY("View History"),
        COMPRESS_HISTORY("Compress History"),
        EDIT_FILE("Edit File"),
        READ_FILE("Read File"),
        SUMMARIZE_FILE("Summarize File"),
        EDIT_ALL_REFS("Edit all References"),
        READ_ALL_REFS("Read all References"),
        SUMMARIZE_ALL_REFS("Summarize all References"),
        COPY("Copy"),
        DROP("Drop"),
        DROP_ALL("Drop All"),
        COPY_ALL("Copy All"),
        PASTE("Paste text, images, urls");

        private final String label;

        WorkspaceAction(String label) {
            this.label = label;
        }

        public AbstractAction createAction(WorkspacePanel panel) {
            return new AbstractAction(label) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    switch (WorkspaceAction.this) {
                        case DROP_ALL -> panel.performContextActionAsync(ContextAction.DROP, List.of());
                        case COPY_ALL -> panel.performContextActionAsync(ContextAction.COPY, List.of());
                        case PASTE -> panel.performContextActionAsync(ContextAction.PASTE, List.of());
                        case COMPRESS_HISTORY -> panel.contextManager.compressHistoryAsync();
                        default -> throw new UnsupportedOperationException("Action not implemented: " + WorkspaceAction.this);
                    }
                }
            };
        }

        public AbstractAction createDisabledAction(String tooltip) {
            var action = new AbstractAction(label) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    // Disabled actions do nothing
                }
            };
            action.setEnabled(false);
            action.putValue(Action.SHORT_DESCRIPTION, tooltip);
            return action;
        }

        public AbstractAction createFileAction(WorkspacePanel panel, ProjectFile file) {
            return new AbstractAction(label) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    switch (WorkspaceAction.this) {
                        case SHOW_IN_PROJECT -> panel.chrome.showFileInProjectTree(file);
                        case VIEW_FILE -> {
                            var fragment = new ContextFragment.ProjectPathFragment(file, panel.contextManager);
                            panel.showFragmentPreview(fragment);
                        }
                        case VIEW_HISTORY -> panel.chrome.getGitPanel().addFileHistoryTab(file);
                        default -> throw new UnsupportedOperationException("File action not implemented: " + WorkspaceAction.this);
                    }
                }
            };
        }

        public AbstractAction createFileRefAction(WorkspacePanel panel, TableUtils.FileReferenceList.FileReferenceData fileRef) {
            var baseName = this == EDIT_FILE ? "Edit " : this == READ_FILE ? "Read " : "Summarize ";
            return new AbstractAction(baseName + fileRef.getFullPath()) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (fileRef.getRepoFile() != null) {
                        var contextAction = switch (WorkspaceAction.this) {
                            case EDIT_FILE -> ContextAction.EDIT;
                            case READ_FILE -> ContextAction.READ;
                            case SUMMARIZE_FILE -> ContextAction.SUMMARIZE;
                            default -> throw new UnsupportedOperationException("File ref action not implemented: " + WorkspaceAction.this);
                        };
                        var fragment = new ContextFragment.ProjectPathFragment(fileRef.getRepoFile(), panel.contextManager);
                        panel.performContextActionAsync(contextAction, List.of(fragment));
                    } else {
                        panel.chrome.toolError("Cannot " + label.toLowerCase(Locale.ROOT) + ": " + fileRef.getFullPath() + " - no ProjectFile available");
                    }
                    
                    // Apply edit restrictions
                    if (WorkspaceAction.this == EDIT_FILE) {
                        var project = panel.contextManager.getProject();
                        var repoFile = fileRef.getRepoFile();
                        if (!panel.hasGit()) {
                            setEnabled(false);
                            putValue(Action.SHORT_DESCRIPTION, "Editing not available without Git");
                        } else if (repoFile == null) {
                            setEnabled(false);
                            putValue(Action.SHORT_DESCRIPTION, "Editing not available for external files");
                        } else if (!project.getRepo().getTrackedFiles().contains(repoFile)) {
                            setEnabled(false);
                            putValue(Action.SHORT_DESCRIPTION, "Cannot edit untracked file: " + fileRef.getFullPath());
                        }
                    }
                }
            };
        }

        public AbstractAction createFragmentAction(WorkspacePanel panel, ContextFragment fragment) {
            return new AbstractAction(label) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    switch (WorkspaceAction.this) {
                        case VIEW_FILE, SHOW_CONTENTS -> panel.showFragmentPreview(fragment);
                        default -> throw new UnsupportedOperationException("Fragment action not implemented: " + WorkspaceAction.this);
                    }
                }
            };
        }

        public AbstractAction createFragmentsAction(WorkspacePanel panel, List<ContextFragment> fragments) {
            return new AbstractAction(label) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    var contextAction = switch (WorkspaceAction.this) {
                        case EDIT_ALL_REFS -> ContextAction.EDIT;
                        case READ_ALL_REFS -> ContextAction.READ;
                        case SUMMARIZE_ALL_REFS -> ContextAction.SUMMARIZE;
                        case COPY -> ContextAction.COPY;
                        case DROP -> ContextAction.DROP;
                        default -> throw new UnsupportedOperationException("Fragments action not implemented: " + WorkspaceAction.this);
                    };
                    panel.performContextActionAsync(contextAction, fragments);
                    
                    // Apply enable/disable logic for specific actions
                    if (WorkspaceAction.this == EDIT_ALL_REFS) {
                        if (!panel.allTrackedProjectFiles(fragments)) {
                            var hasFiles = panel.hasFiles(fragments);
                            setEnabled(false);
                            if (!hasFiles) {
                                putValue(Action.SHORT_DESCRIPTION, "No files associated with the selection to edit.");
                            } else {
                                putValue(Action.SHORT_DESCRIPTION, "Cannot edit because selection includes untracked or external files.");
                            }
                        }
                    } else if (WorkspaceAction.this == READ_ALL_REFS || WorkspaceAction.this == SUMMARIZE_ALL_REFS) {
                        if (!panel.hasFiles(fragments)) {
                            setEnabled(false);
                            var actionName = WorkspaceAction.this == READ_ALL_REFS ? "read" : "summarize";
                            putValue(Action.SHORT_DESCRIPTION, "No files associated with the selection to " + actionName + ".");
                        }
                    }
                }
            };
        }
    }

    /**
     * Immutable record containing description text and associated file references
     */
    public static record DescriptionWithReferences(
        String description,
        List<TableUtils.FileReferenceList.FileReferenceData> fileReferences,
        ContextFragment fragment
    ) {
        public DescriptionWithReferences {
            // Defensive copy for immutability
            fileReferences = List.copyOf(fileReferences != null ? fileReferences : List.of());
        }
        
        @Override
        public String toString() {
            return description; // For backward compatibility with existing code that expects String
        }
    }

    /**
     * Custom table cell renderer for the Description column that displays
     * description text on top and file reference badges below.
     */
    private static class DescriptionWithBadgesRenderer implements javax.swing.table.TableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                      boolean isSelected, boolean hasFocus,
                                                      int row, int column) {
            // Create main panel with vertical layout
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setOpaque(true);
            
            // Set colors based on selection
            if (isSelected) {
                panel.setBackground(table.getSelectionBackground());
                panel.setForeground(table.getSelectionForeground());
            } else {
                panel.setBackground(table.getBackground());
                panel.setForeground(table.getForeground());
            }
            
            // Extract data from the DescriptionWithReferences record
            DescriptionWithReferences data = (DescriptionWithReferences) value;
            if (data == null) {
                return panel; // Return empty panel if no data
            }
            
            String description = data.description();
            List<TableUtils.FileReferenceList.FileReferenceData> fileReferences = data.fileReferences();
            
            // Create description label
            JLabel descLabel = new JLabel(description);
            descLabel.setOpaque(false);
            descLabel.setForeground(panel.getForeground());
            descLabel.setVerticalAlignment(SwingConstants.TOP); // Ensure baseline alignment with LOC column
            
            // Add description to panel
            descLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(descLabel);
            
            // Add file badges if there are any
            if (!fileReferences.isEmpty()) {
                // Add small vertical gap
                panel.add(Box.createVerticalStrut(3));
                
                // Calculate available width for badges (table column width minus padding)
                int availableWidth = table.getColumnModel().getColumn(column).getWidth() - 20; // Leave some padding
                
                // Create adaptive file reference list
                var badgeList = new TableUtils.FileReferenceList.AdaptiveFileReferenceList(
                        fileReferences, availableWidth, 4);
                badgeList.setOpaque(false);
                badgeList.setAlignmentX(Component.LEFT_ALIGNMENT);
                
                // Set badge colors based on selection
                badgeList.setSelected(isSelected);
                
                panel.add(badgeList);
            }
            
            // Calculate preferred height
            int preferredHeight = calculatePreferredHeight(panel);
            
            // Set row height if different from current
            if (table.getRowHeight(row) != preferredHeight) {
                SwingUtilities.invokeLater(() -> table.setRowHeight(row, preferredHeight));
            }
            
            return panel;
        }
        
        private int calculatePreferredHeight(JPanel panel) {
            // Force layout to get accurate measurements
            panel.doLayout();
            return panel.getPreferredSize().height + 4; // Add small padding
        }
    }

    /**
     * Helper for building popup menus consistently
     */
    public static class PopupBuilder {
        private final JPopupMenu popup;
        private final Chrome chrome;

        private PopupBuilder(Chrome chrome) {
            this.popup = new JPopupMenu();
            this.chrome = chrome;
        }

        public static PopupBuilder create(Chrome chrome) {
            return new PopupBuilder(chrome);
        }

        public PopupBuilder add(List<Action> actions) {
            for (var action : actions) {
                if (action == null) {
                    popup.addSeparator();
                } else {
                    popup.add(new JMenuItem(action));
                }
            }
            return this;
        }

        public PopupBuilder addSeparator() {
            popup.addSeparator();
            return this;
        }

        public void show(Component invoker, int x, int y) {
            if (chrome.themeManager != null) {
                chrome.themeManager.registerPopupMenu(popup);
            }
            popup.show(invoker, x, y);
        }
    }

    // Columns
    public static final int LOC_COLUMN = 0;
    public static final int DESCRIPTION_COLUMN = 1;
    private final int FRAGMENT_COLUMN = 2;
    
    // Column dimensions
    private static final int LOC_COLUMN_WIDTH = 55;
    private static final int LOC_COLUMN_RIGHT_PADDING = 6;
    private static final int DESCRIPTION_COLUMN_WIDTH = 480;

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

    private static final String READ_ONLY_TIP = "Select latest activity to enable";
    private static final String COPY_ALL_ACTION_CMD = "workspace.copyAll";

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
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        buildContextPanel();

        safeGetLabel(0).setText(EMPTY_CONTEXT);
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
        DefaultTableModel tableModel = new DefaultTableModel(new Object[]{"LOC", "Description", "Fragment"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return switch (columnIndex) {
                    case LOC_COLUMN -> Integer.class;
                    case DESCRIPTION_COLUMN -> DescriptionWithReferences.class;
                    case FRAGMENT_COLUMN -> ContextFragment.class;
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

        // Add custom cell renderer for the "Description" column that includes badges
        contextTable.getColumnModel().getColumn(DESCRIPTION_COLUMN).setCellRenderer(new DescriptionWithBadgesRenderer());
        
        // Set right alignment for LOC column numbers with font metrics-based baseline alignment
        var locRenderer = new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                          boolean isSelected, boolean hasFocus,
                                                          int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                
                // Calculate baseline-aligned padding using font metrics - match TableUtils calculation
                var tableFont = table.getFont();
                var fontMetrics = table.getFontMetrics(tableFont);
                
                // Use a small offset to align with description text baseline (similar to TableUtils approach)
                int baselineOffset = fontMetrics.getLeading() / 2; // Half the leading for better alignment
                
                setBorder(BorderFactory.createEmptyBorder(baselineOffset, 0, 0, LOC_COLUMN_RIGHT_PADDING));
                return c;
            }
        };
        locRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
        locRenderer.setVerticalAlignment(SwingConstants.TOP);
        contextTable.getColumnModel().getColumn(LOC_COLUMN).setCellRenderer(locRenderer);

        // Remove file references column setup - badges will be in description column

        // Hide the FRAGMENT_COLUMN from view
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setMinWidth(0);
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setMaxWidth(0);
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setWidth(0);

        // LOC column: precise width so right-aligned numbers align with description text start
        contextTable.getColumnModel().getColumn(LOC_COLUMN).setPreferredWidth(LOC_COLUMN_WIDTH);
        contextTable.getColumnModel().getColumn(LOC_COLUMN).setMaxWidth(LOC_COLUMN_WIDTH);
        contextTable.getColumnModel().getColumn(LOC_COLUMN).setMinWidth(LOC_COLUMN_WIDTH);
        contextTable.getColumnModel().getColumn(DESCRIPTION_COLUMN).setPreferredWidth(DESCRIPTION_COLUMN_WIDTH);

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
                // If it's a popup trigger, let the table's main popup handler deal with it.
                if (!e.isPopupTrigger()) {
                    // Handle badge clicks in the new description column layout
                    int col = contextTable.columnAtPoint(e.getPoint());
                    if (col == DESCRIPTION_COLUMN) { // Description column
                        int row = contextTable.rowAtPoint(e.getPoint());
                        if (row >= 0) {
                            // Check if the cell contains file references
                            var descriptionData = (DescriptionWithReferences) contextTable.getModel().getValueAt(row, DESCRIPTION_COLUMN);
                            if (descriptionData != null && !descriptionData.fileReferences().isEmpty()) {
                                // Check if the click actually hit a badge (not just anywhere in the cell)
                                var clickedRef = TableUtils.findClickedReference(e.getPoint(), row, DESCRIPTION_COLUMN, contextTable, descriptionData.fileReferences());
                                
                                if (clickedRef != null) {
                                    // Use ContextMenuUtils directly now that we have the proper data structure
                                    ContextMenuUtils.handleFileReferenceClick(
                                            e,
                                            contextTable,
                                            chrome,
                                            () -> {}, // Workspace doesn't need to refresh suggestions
                                            DESCRIPTION_COLUMN
                                    );
                                }
                            }
                        }
                    }
                }
            }
        });

        // Add mouse motion for tooltips
        contextTable.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int row = contextTable.rowAtPoint(e.getPoint());
                int col = contextTable.columnAtPoint(e.getPoint());
                if (row >= 0 && col == DESCRIPTION_COLUMN) {
                    // Show full description - badge tooltips will be handled by new renderer
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
                if (e.isConsumed()) return;
                if (!e.isPopupTrigger()) return;

                int row = contextTable.rowAtPoint(e.getPoint());
                
                // Handle COPY_ONLY mode
                if (popupMenuMode == PopupMenuMode.COPY_ONLY) {
                    if (row >= 0) {
                        if (contextTable.getSelectedRowCount() == 0
                                || !Arrays.stream(contextTable.getSelectedRows()).anyMatch(r -> r == row)) {
                            contextTable.setRowSelectionInterval(row, row);
                        }
                        JMenuItem copyItem = new JMenuItem("Copy to Active Workspace");
                        copyItem.addActionListener(ev -> {
                            var fragsToCopy = getSelectedFragments();
                            contextManager.addFilteredToContextAsync(currentContext, fragsToCopy);
                        });
                        contextMenu.removeAll();
                        contextMenu.add(copyItem);
                        if (chrome.themeManager != null) {
                            chrome.themeManager.registerPopupMenu(contextMenu);
                        }
                        contextMenu.show(contextTable, e.getX(), e.getY());
                    }
                    return;
                }

                // Ensure row selection
                if (row >= 0) {
                    boolean rowIsSelected = Arrays.stream(contextTable.getSelectedRows()).anyMatch(r -> r == row);
                    if (!rowIsSelected) {
                        contextTable.setRowSelectionInterval(row, row);
                    }
                }

                List<ContextFragment> selectedFragments = getSelectedFragments();

                // Show empty table menu if no selection
                if (row < 0 || selectedFragments.isEmpty()) {
                    tablePopupMenu.show(contextTable, e.getX(), e.getY());
                    if (chrome.themeManager != null) {
                        chrome.themeManager.registerPopupMenu(tablePopupMenu);
                    }
                    return;
                }

                // Classify scenario and build menu
                PopupScenario scenario = classifyScenario(e, selectedFragments);
                List<Action> actions = scenario.getActions(WorkspacePanel.this);
                
                PopupBuilder.create(chrome)
                    .add(actions)
                    .show(contextTable, e.getX(), e.getY());
                
                e.consume();
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
        copyAllMenuItem.setActionCommand(COPY_ALL_ACTION_CMD);
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
        locSummaryPanel = new JPanel();
        locSummaryPanel.setLayout(new BoxLayout(locSummaryPanel, BoxLayout.Y_AXIS));
        JLabel innerLabel = new JLabel(" ");
        innerLabel.setBorder(new EmptyBorder(5, 5, 2, 5));
        JLabel costLabel = new JLabel(" ");
        costLabel.setBorder(new EmptyBorder(0, 5, 5, 5));
        locSummaryPanel.add(innerLabel);
        locSummaryPanel.add(costLabel);
        locSummaryPanel.setBorder(BorderFactory.createEmptyBorder());
        contextSummaryPanel.add(locSummaryPanel, BorderLayout.NORTH);

        // Warning panel (for red/yellow context size warnings)
        warningPanel = new JPanel(new BorderLayout()); // Changed to BorderLayout
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
        WorkspacePanel.this.addMouseListener(panelPopupAndFocusListener);
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
            var tableModel = (DefaultTableModel) contextTable.getModel();
            return Arrays.stream(contextTable.getSelectedRows())
                    .mapToObj(row -> (ContextFragment) tableModel.getValueAt(row, FRAGMENT_COLUMN))
                    .collect(Collectors.toList());
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
            // Reset summary label
            safeGetLabel(0).setText(EMPTY_CONTEXT);

            // Clear and hide the cost label
            var cost = safeGetLabel(1);
            cost.setText(" ");
            cost.setVisible(false);

            // Remove any warning messages
            warningPanel.removeAll();

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

            // Build file references for the record
            List<TableUtils.FileReferenceList.FileReferenceData> fileReferences = new ArrayList<>();
            if (frag.getType() != ContextFragment.FragmentType.PROJECT_PATH) {
                fileReferences = frag.files()
                        .stream()
                        .map(file -> new TableUtils.FileReferenceList.FileReferenceData(file.getFileName(), file.toString(), file))
                        .distinct()
                        .sorted(Comparator.comparing(TableUtils.FileReferenceList.FileReferenceData::getFileName))
                        .collect(Collectors.toList());
            }

            // Create rich description object
            var descriptionWithRefs = new DescriptionWithReferences(desc, fileReferences, frag);
            tableModel.addRow(new Object[]{locText, descriptionWithRefs, frag});
        }

        var approxTokens = Messages.getApproximateTokens(fullText.toString());
        var innerLabel = safeGetLabel(0);
        var costLabel = safeGetLabel(1);

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
        innerLabel.setText("Total: %,d LOC, or about %,dk tokens.".formatted(totalLines, approxTokens / 1000));
        innerLabel.setToolTipText(null); // Clear tooltip

        if (costEstimates.isEmpty()) {
            costLabel.setText(" ");
            costLabel.setVisible(false);
        } else {
            costLabel.setText("Estimated cost/request is " + String.join(", ", costEstimates));
            costLabel.setVisible(true);
        }

        // Remove any existing warning labels from the warningPanel
        warningPanel.removeAll();

        if (!redWarningModels.isEmpty()) {
            String modelListStr = redWarningModels.entrySet().stream()
                    .map(entry -> String.format("%s (%,d)", entry.getKey(), entry.getValue()))
                    .collect(Collectors.joining(", "));
            String warningText = String.format("Warning! Your Workspace (~%,d tokens) fills more than 90%% of the context window for the following models: %s. Performance will be degraded.", approxTokens, modelListStr);
            
            JTextArea warningArea = createWarningTextArea(warningText, Color.RED, warningTooltip);
            warningPanel.add(warningArea, BorderLayout.CENTER);

        } else if (!yellowWarningModels.isEmpty()) {
            String modelListStr = yellowWarningModels.entrySet().stream()
                    .map(entry -> String.format("%s (%,d)", entry.getKey(), entry.getValue()))
                    .collect(Collectors.joining(", "));
            String warningText = String.format("Warning! Your Workspace (~%,d tokens) fills more than half of the context window for the following models: %s. Performance may be degraded.", approxTokens, modelListStr);
            
            JTextArea warningArea = createWarningTextArea(warningText, Color.YELLOW, warningTooltip); // Standard yellow might be hard to see on some themes
            warningPanel.add(warningArea, BorderLayout.CENTER);
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

    private JTextArea createWarningTextArea(String text, Color foregroundColor, String tooltip) {
        JTextArea textArea = new JTextArea(text);
        textArea.setWrapStyleWord(true);
        textArea.setLineWrap(true);
        textArea.setEditable(false);
        textArea.setFocusable(false);
        textArea.setOpaque(false);
        textArea.setFont(UIManager.getFont("Label.font"));
        textArea.setForeground(foregroundColor);
        textArea.setToolTipText(tooltip);
        textArea.setBorder(null);
        return textArea;
    }

    /**
     * Shows a preview of the fragment contents
     */
    private void showFragmentPreview(ContextFragment fragment) {
        chrome.openFragmentPreview(fragment);
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
        if (!contextManager.getAnalyzerWrapper().isReady()) {
            chrome.systemNotify(AnalyzerWrapper.ANALYZER_BUSY_MESSAGE,
                              AnalyzerWrapper.ANALYZER_BUSY_TITLE,
                              JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        return true;
    }

    /**
     * Returns true if the project has Git support
     */
    private boolean hasGit() {
        return contextManager.getProject().hasGit();
    }

    /**
     * Returns true if the fragments have associated files
     */
    private boolean hasFiles(List<ContextFragment> fragments) {
        return fragments.stream()
                .flatMap(frag -> frag.files().stream())
                .findAny()
                .isPresent();
    }

    /**
     * Returns true if all files from the fragments are tracked project files
     */
    private boolean allTrackedProjectFiles(List<ContextFragment> fragments) {
        var project = contextManager.getProject();
        var allFiles = fragments.stream()
                .flatMap(frag -> frag.files().stream())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        
        return !allFiles.isEmpty() && allFiles.stream()
                .filter(ProjectFile.class::isInstance)
                .map(ProjectFile.class::cast)
                .allMatch(pf -> pf.exists() && project.getRepo().getTrackedFiles().contains(pf));
    }

    /**
     * Classifies the popup scenario based on the mouse event and table state
     */
    private PopupScenario classifyScenario(MouseEvent e, List<ContextFragment> selectedFragments) {
        int row = contextTable.rowAtPoint(e.getPoint());
        int col = contextTable.columnAtPoint(e.getPoint());

        // Handle file badge clicks in new description column layout
        if (col == DESCRIPTION_COLUMN && row >= 0) { // Description column
            ContextFragment fragment = (ContextFragment) contextTable.getModel().getValueAt(row, FRAGMENT_COLUMN);
            if (fragment != null && fragment.getType() != ContextFragment.FragmentType.PROJECT_PATH) {
                var fileReferences = fragment.files()
                        .stream()
                        .map(file -> new TableUtils.FileReferenceList.FileReferenceData(file.getFileName(), file.toString(), file))
                        .distinct()
                        .sorted(Comparator.comparing(TableUtils.FileReferenceList.FileReferenceData::getFileName))
                        .collect(Collectors.toList());
                
                if (!fileReferences.isEmpty()) {
                    // We need to determine if the click was specifically on a badge
                    Rectangle cellRect = contextTable.getCellRect(row, col, false);
                    int yInCell = e.getPoint().y - cellRect.y;
                    
                    // Estimate if click is in lower half of cell (where badges are)
                    if (yInCell > cellRect.height / 2) {
                        // Try to find which specific badge was clicked
                        TableUtils.FileReferenceList.FileReferenceData clickedFileRef =
                                TableUtils.findClickedReference(e.getPoint(), row, col, contextTable, fileReferences);
                        
                        if (clickedFileRef != null && clickedFileRef.getRepoFile() != null) {
                            return new FileBadge(clickedFileRef);
                        }
                    }
                }
            }
        }

        // Handle selection-based scenarios
        if (selectedFragments.isEmpty()) {
            return new NoSelection();
        } else if (selectedFragments.size() == 1) {
            return new SingleFragment(selectedFragments.getFirst());
        } else {
            return new MultiFragment(selectedFragments);
        }
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
                    chrome.toolError("Code Intelligence is empty; nothing to add");
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
                    chrome.toolError("Code Intelligence is empty; nothing to add");
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
                    chrome.toolError("Code Intelligence is empty; nothing to add");
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
            var files = selectedFragments.stream()
                                        .flatMap(fragment -> fragment.files().stream())
                                        .filter(Objects::nonNull)
                                        .collect(Collectors.toSet());
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
            var files = selectedFragments.stream()
                               .flatMap(frag -> frag.files().stream())
                               .collect(Collectors.toSet());
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
            chrome.toolError("Clipboard is empty or unavailable");
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
                        chrome.toolError("Unable to paste image data from Windows to Brokk running under WSL. This is a limitation of WSL. You can write the image to a file and read it that way instead.");
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
                    chrome.toolError("Clipboard text is empty");
                    return;
                }
            } catch (Exception e) {
                chrome.toolError("Failed to read clipboard text: " + e.getMessage());
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
                    if (ImageUtil.isImageUri(uri, httpClient)) {
                        try {
                            chrome.systemOutput("Fetching image from " + clipboardText);
                            java.awt.Image image = ImageUtil.downloadImage(uri, httpClient);
                            if (image != null) {
                                contextManager.addPastedImageFragment(image);
                                chrome.systemOutput("Pasted image from URL added to context");
                                chrome.actionComplete();
                                return; // Image handled, done with paste action
                            } else {
                                logger.warn("URL {} identified as image by ImageUtil, but downloadImage returned null. Falling back to text.", clipboardText);
                                chrome.systemOutput("Could not load image from URL. Trying to fetch as text.");
                            }
                        } catch (Exception e) { // Catching general exception as downloadImage might throw various things indirectly
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
                        chrome.toolError("Failed to fetch or process URL content as text: " + e.getMessage());
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
            chrome.toolError("Unsupported clipboard content type");
        }
    }

    private void doDropAction(List<? extends ContextFragment> selectedFragments) {
        if (selectedFragments.isEmpty()) {
            if (contextManager.topContext().isEmpty()) { 
                chrome.systemOutput("No context to drop");
                return;
            }
            contextManager.dropAll(); 
        } else {
            for (var frag : selectedFragments) {
                if (frag.getType() == ContextFragment.FragmentType.HISTORY) {
                    contextManager.clearHistory();
                    chrome.systemOutput("Cleared task history");
                    break;
                }
            }

            contextManager.drop(selectedFragments); // Use the new ID-based method
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
            selectedFragments.stream()
                    .flatMap(frag -> frag.files().stream())
                    .forEach(selectedFiles::add);
        }

        if (selectedFiles.isEmpty() && selectedClasses.isEmpty()) {
            chrome.toolError("No files or classes identified for summarization in the selection.");
            return;
        }

        // Call the updated addSummaries method, which outputs a message on success
        boolean success = contextManager.addSummaries(selectedFiles, selectedClasses);
        if (!success) {
            chrome.toolError("No summarizable content found in the selected files or symbols.");
        }
    }

    /**
     * Cast BrokkFile to ProjectFile. Will throw if ExternalFiles are present.
     * Use with caution, only when external files are disallowed or handled separately.
     */
    private List<ProjectFile> toProjectFilesUnsafe(List<BrokkFile> files) {
        return files == null ? List.of() : files.stream()
                .map(f -> {
                    if (f instanceof ProjectFile pf) {
                        return pf;
                    }
                    throw new ClassCastException("Expected only ProjectFile but got " + f.getClass().getName());
                })
                .toList();
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

    /**
     * Return the JLabel stored in {@code locSummaryPanel} at the given index or throw
     */
    private JLabel safeGetLabel(int index) {
        return switch (locSummaryPanel.getComponent(index)) {
            case JLabel lbl -> lbl;
            default -> throw new IllegalStateException(
                    "Expected JLabel at locSummaryPanel index " + index);
        };
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
            refreshMenuState();
        });
    }

    private void refreshMenuState() {
        if (tablePopupMenu == null) {
            return;
        }
        var editable = workspaceCurrentlyEditable;
        for (var component : tablePopupMenu.getComponents()) {
            if (component instanceof JMenuItem mi) {
                // "Copy All" is always enabled.
                // Other JMenuItems (including JMenu "Add") are enabled based on workspace editability.
                boolean copyAll = COPY_ALL_ACTION_CMD.equals(mi.getActionCommand());
                mi.setEnabled(editable || copyAll);
                if (copyAll || editable) {
                    mi.setToolTipText(null);
                } else {
                    mi.setToolTipText(READ_ONLY_TIP);
                }
            }
        }
    }
}
