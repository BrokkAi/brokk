package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.SessionManager;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.context.FrozenFragment;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * A standalone Swing GUI for exploring Brokk context sessions. It displays a list of sessions, the contexts and
 * fragments within a selected session, and the content of a selected fragment.
 */
public final class ContextExplorer extends JFrame {
    private static final Logger logger = LogManager.getLogger(ContextExplorer.class);
    private final SessionManager sessionManager;
    private final IContextManager contextManager; // A minimal stub for history loading

    // Pane 1: Sessions List (Left)
    private final DefaultListModel<SessionManager.SessionInfo> sessionListModel = new DefaultListModel<>();
    private final JList<SessionManager.SessionInfo> sessionsList = new JList<>(sessionListModel);

    // Pane 2: Contexts and Fragments Table (Center)
    private final ContextFragmentsTableModel tableModel = new ContextFragmentsTableModel();
    private final JTable table = new JTable(tableModel);

    // Pane 3: Fragment Content Preview (Right)
    private final JPanel previewPanel = new JPanel(new CardLayout());
    private final JTextArea textArea = new JTextArea();
    private final JLabel imageLabel = new JLabel("", SwingConstants.CENTER);
    private static final String CARD_TEXT = "text";
    private static final String CARD_IMAGE = "image";
    private static final String CARD_EMPTY = "empty"; // For initial or no selection state

    public ContextExplorer(Path sessionsDir) {
        super("Brokk Context Explorer");
        this.contextManager = new MinimalContextManager();
        this.sessionManager = new SessionManager(sessionsDir);

        buildUi();
        loadSessions();
        wireEvents();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private void buildUi() {
        // --- Pane 1: Sessions List ---
        sessionsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sessionsList.setCellRenderer(new SessionInfoListCellRenderer());
        sessionsList.setBorder(new EmptyBorder(5, 5, 5, 5));
        var leftScroll = new JScrollPane(sessionsList);
        leftScroll.setMinimumSize(new Dimension(200, 0));

        // --- Pane 2: Contexts and Fragments Table ---
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultRenderer(Object.class, new FragmentTableCellRenderer(tableModel));
        table.getTableHeader().setReorderingAllowed(false); // Preserve the order of contexts/fragments
        var tableScroll = new JScrollPane(table);
        tableScroll.setMinimumSize(new Dimension(400, 0));

        // --- Pane 3: Fragment Content Preview ---
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        var textScroll = new JScrollPane(textArea);

        var emptyPanel = new JPanel(); // For initial empty state
        previewPanel.add(emptyPanel, CARD_EMPTY);
        previewPanel.add(textScroll, CARD_TEXT);
        previewPanel.add(new JScrollPane(imageLabel), CARD_IMAGE); // Use JScrollPane for image too, in case it's large

        var rightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, previewPanel);
        rightSplit.setResizeWeight(0.6); // Table takes 60%, preview takes 40%

        var mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, rightSplit);
        mainSplit.setResizeWeight(0.2); // Sessions list takes 20%, rightSplit takes 80%

        add(mainSplit, BorderLayout.CENTER);
    }

    private void loadSessions() {
        new SwingWorker<List<SessionManager.SessionInfo>, Void>() {
            @Override
            protected List<SessionManager.SessionInfo> doInBackground() {
                return sessionManager.listSessions();
            }

            @Override
            protected void done() {
                try {
                    var sessions = get();
                    sessionListModel.clear();
                    sessions.forEach(sessionListModel::addElement);
                    if (!sessions.isEmpty()) {
                        sessionsList.setSelectedIndex(0); // Select the first session by default
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("Session list loading interrupted", e);
                    JOptionPane.showMessageDialog(
                            ContextExplorer.this,
                            "Session list loading interrupted.",
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                } catch (ExecutionException e) {
                    logger.error("Failed to list sessions", e);
                    var cause = e.getCause();
                    var msg = (cause != null && cause.getMessage() != null) ? cause.getMessage() : e.toString();
                    JOptionPane.showMessageDialog(
                            ContextExplorer.this,
                            "Failed to list sessions: " + msg,
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void wireEvents() {
        sessionsList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                var info = sessionsList.getSelectedValue();
                if (info != null) {
                    loadSessionHistory(info);
                } else {
                    tableModel.clear();
                    clearPreview();
                }
            }
        });

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int selectedRow = table.getSelectedRow();
                if (selectedRow >= 0) {
                    int modelRow = table.convertRowIndexToModel(selectedRow); // Handle sorting/filtering
                    var item = tableModel.getRow(modelRow);
                    if (item instanceof FragmentRow fr) {
                        showFragment(fr.fragment());
                    } else { // HeaderRow selected
                        clearPreview();
                    }
                } else {
                    clearPreview();
                }
            }
        });
    }

    private void loadSessionHistory(SessionManager.SessionInfo info) {
        tableModel.clear();
        clearPreview();

        new SwingWorker<List<TableRow>, Void>() {
            @Override
            protected List<TableRow> doInBackground() throws Exception {
                var ch = sessionManager.loadHistory(info.id(), contextManager);
                if (ch == null) {
                    throw new IOException(
                            "Unable to load history for session " + info.name() + " (ID: " + info.id() + ")");
                }

                List<TableRow> rows = new ArrayList<>();
                int contextIndex = 1;
                for (var ctx : ch.getHistory()) {
                    int historyEntries = ctx.getTaskHistory().size();
                    int historyLines = countTaskHistoryLines(ctx);
                    var header = new HeaderRow(contextIndex, ctx.id(), safeAction(ctx), historyEntries, historyLines);
                    rows.add(header);

                    for (var fragment : ctx.allFragments().toList()) {
                        boolean isText = safeIsText(fragment);
                        int lines = isText ? safeLineCount(fragment) : 0;
                        rows.add(new FragmentRow(ctx.id(), fragment, lines));
                    }

                    var parsed = ctx.getParsedOutput();
                    if (parsed != null) {
                        boolean isText = safeIsText(parsed);
                        int lines = isText ? safeLineCount(parsed) : 0;
                        rows.add(new FragmentRow(ctx.id(), parsed, lines));
                    }

                    contextIndex++;
                }
                return rows;
            }

            @Override
            protected void done() {
                try {
                    tableModel.setRows(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("Session history loading interrupted for session {}", info.id(), e);
                    JOptionPane.showMessageDialog(
                            ContextExplorer.this,
                            "Session history loading interrupted.",
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                } catch (ExecutionException e) {
                    logger.error("Failed to load session history for session {}", info.id(), e);
                    var cause = e.getCause();
                    var msg = (cause != null && cause.getMessage() != null) ? cause.getMessage() : e.toString();
                    JOptionPane.showMessageDialog(
                            ContextExplorer.this,
                            "Failed to load session history: " + msg,
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private static String safeAction(Context ctx) {
        try {
            // Context.getAction() already handles Future completion with a timeout
            return ctx.getAction();
        } catch (Exception e) {
            logger.warn("Error getting action for context {}: {}", ctx.id(), e.getMessage());
            return "(Summary Unavailable)";
        }
    }

    private static boolean safeIsText(ContextFragment f) {
        try {
            return f.isText();
        } catch (Exception e) {
            logger.warn("Error checking isText for fragment {}: {}", f.id(), e.getMessage());
            return true; // Assume text and let text() fail if it's actually not, will be handled by showFragment
        }
    }

    private static int safeLineCount(ContextFragment f) {
        try {
            var t = f.text();
            return t.isEmpty() ? 0 : (int) t.lines().count();
        } catch (Exception e) {
            logger.warn("Error getting line count for fragment {}: {}", f.id(), e.getMessage());
            return 0;
        }
    }

    private static int countTaskHistoryLines(Context ctx) {
        try {
            return ctx.getTaskHistory().stream()
                    .mapToInt(te -> {
                        try {
                            if (te.log() != null) {
                                var t = te.log().text();
                                return t.isEmpty() ? 0 : (int) t.lines().count();
                            } else if (te.summary() != null) {
                                var s = te.summary();
                                return s.isEmpty() ? 0 : (int) s.lines().count();
                            }
                        } catch (Exception e) {
                            logger.warn(
                                    "Error computing line count for TaskEntry {} in context {}: {}",
                                    te.sequence(),
                                    ctx.id(),
                                    e.getMessage());
                        }
                        return 0;
                    })
                    .sum();
        } catch (Exception e) {
            logger.warn("Error computing task history line count for context {}: {}", ctx.id(), e.getMessage());
            return 0;
        }
    }

    private void showFragment(ContextFragment fragment) {
        clearPreview(); // Clear before loading new content

        new SwingWorker<Void, Void>() {
            @Nullable
            String textContent = null;

            @Nullable
            Image imageContent = null;

            boolean failed = false;

            @Override
            protected Void doInBackground() {
                try {
                    if (fragment.isText()) {
                        textContent = fragment.text();
                    } else {
                        // For non-text fragments, try to get image
                        // FrozenFragment is the common type coming from history for images
                        if (fragment instanceof FrozenFragment ff) {
                            imageContent = ff.image();
                        } else if (fragment instanceof ContextFragment.ImageFileFragment ifd) {
                            // If it's a live ImageFileFragment, try its image() method
                            imageContent = ifd.image();
                        } else if (fragment instanceof ContextFragment.AnonymousImageFragment aif) {
                            imageContent = aif.image();
                        } else {
                            // If it's another non-text type, we can't display it directly as image or text
                            textContent =
                                    "Fragment type " + fragment.getType() + " is not a displayable image or text.";
                        }
                    }
                } catch (Exception e) {
                    failed = true;
                    textContent = "Error loading content for fragment " + fragment.id() + ": " + e.getMessage();
                    logger.error("Failed to load content for fragment {}", fragment.id(), e);
                }
                return null;
            }

            @Override
            protected void done() {
                CardLayout cl = (CardLayout) previewPanel.getLayout();
                if (failed || textContent != null) {
                    textArea.setText(textContent != null ? textContent : "(No content found or error)");
                    textArea.setCaretPosition(0);
                    cl.show(previewPanel, CARD_TEXT);
                } else if (imageContent != null) {
                    imageLabel.setIcon(new ImageIcon(imageContent));
                    imageLabel.setText(""); // Clear any previous text
                    cl.show(previewPanel, CARD_IMAGE);
                } else {
                    textArea.setText("Fragment content could not be displayed or is empty.");
                    cl.show(previewPanel, CARD_TEXT);
                }
            }
        }.execute();
    }

    private void clearPreview() {
        ((CardLayout) previewPanel.getLayout()).show(previewPanel, CARD_EMPTY);
        textArea.setText("");
        imageLabel.setIcon(null);
        imageLabel.setText("");
    }

    public static void main(String[] args) {
        // Use system look and feel for a native look
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            logger.warn("Could not set system look and feel", e);
        }

        SwingUtilities.invokeLater(() -> {
            Path sessionsDir = chooseSessionsDir();
            if (sessionsDir != null) {
                new ContextExplorer(sessionsDir);
            } else {
                logger.info("User cancelled sessions directory selection. Exiting.");
                System.exit(0); // Exit if user cancels directory selection
            }
        });
    }

    private static @Nullable Path chooseSessionsDir() {
        var chooser = new JFileChooser();
        chooser.setDialogTitle("Select Brokk sessions directory");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setCurrentDirectory(new File(System.getProperty("user.home")));

        // Show dotfiles (hidden files on Unix/macOS)
        chooser.setFileHidingEnabled(false);

        int result = chooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            return chooser.getSelectedFile().toPath();
        }
        return null;
    }

    // --- Helper classes for Table Model and Renderers ---

    /** A sealed interface to distinguish between header and fragment rows in the table. */
    private sealed interface TableRow permits HeaderRow, FragmentRow {}

    /** Represents a header row for a Context history entry. */
    private record HeaderRow(int index, UUID contextId, String action, int historyEntries, int historyLines)
            implements TableRow {}

    /** Represents a fragment row within a Context history entry. */
    private record FragmentRow(UUID contextId, ContextFragment fragment, int lineCount) implements TableRow {}

    /** Custom TableModel for displaying Contexts and their Fragments. */
    private static final class ContextFragmentsTableModel extends AbstractTableModel {
        private final List<TableRow> rows = new ArrayList<>();
        private static final String[] COLUMN_NAMES = {
            "Type", "Context Info", "Fragment ID", "FragType", "Description", "Lines", "Dynamic", "Syntax"
        };

        public void clear() {
            rows.clear();
            fireTableDataChanged();
        }

        public void setRows(List<TableRow> newRows) {
            rows.clear();
            rows.addAll(newRows);
            fireTableDataChanged();
        }

        public TableRow getRow(int modelRow) {
            return rows.get(modelRow);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMN_NAMES.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMN_NAMES[column];
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            var row = rows.get(rowIndex);
            if (row instanceof HeaderRow h) {
                return switch (columnIndex) {
                    case 0 -> "Context";
                    case 1 ->
                        String.format(
                                "#%d: %s (%s) | History: %d entries, %d lines",
                                h.index(),
                                h.action(),
                                shortContext(h.contextId()),
                                h.historyEntries(),
                                h.historyLines());
                    default -> ""; // Empty for other columns in a header row
                };
            } else { // FragmentRow
                var fr = (FragmentRow) row;
                var f = fr.fragment();
                return switch (columnIndex) {
                    case 0 -> "Fragment";
                    case 1 -> shortContext(fr.contextId()); // Parent context ID
                    case 2 -> f.id();
                    case 3 -> f.getType().name();
                    case 4 -> f.shortDescription();
                    case 5 -> fr.lineCount();
                    case 6 -> f.isDynamic();
                    case 7 -> f.syntaxStyle();
                    default -> "";
                };
            }
        }

        private String shortContext(UUID id) {
            String s = id.toString();
            return s.substring(0, 8) + "...";
        }
    }

    /** Custom ListCellRenderer for SessionInfo objects in the JList. */
    private static final class SessionInfoListCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof SessionManager.SessionInfo info) {
                setText(String.format(
                        "%s (%s)", info.name(), info.id().toString().substring(0, 8)));
            }
            // Add tooltip with full text, only when non-empty
            String text = getText();
            setToolTipText((text != null && !text.isEmpty()) ? text : null);
            return this;
        }
    }

    /** Custom TableCellRenderer to style header rows differently. */
    private static final class FragmentTableCellRenderer extends DefaultTableCellRenderer {
        private final ContextFragmentsTableModel model;

        public FragmentTableCellRenderer(ContextFragmentsTableModel model) {
            this.model = model;
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            var c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            var modelRow = table.convertRowIndexToModel(row);
            var item = model.getRow(modelRow);

            if (item instanceof HeaderRow) {
                c.setFont(c.getFont().deriveFont(Font.BOLD));
                c.setBackground(new Color(230, 230, 230)); // Light gray background for headers
                // Headers are not truly selectable, but the table might highlight them.
                // We ensure they always look like headers, overriding selection background for headers.
                if (c instanceof JComponent jc) {
                    if (isSelected) { // Still want to provide visual feedback for selected header, but distinct
                        jc.setBorder(UIManager.getBorder("List.focusCellHighlightBorder"));
                    } else {
                        jc.setBorder(null);
                    }
                }
            } else { // FragmentRow
                c.setFont(c.getFont().deriveFont(Font.PLAIN));
                c.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
                c.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
            }

            // Set tooltip to full cell contents for non-empty values
            if (c instanceof JComponent jc) {
                String tip = value.toString();
                jc.setToolTipText(!tip.isEmpty() ? tip : null);
            }

            return c;
        }
    }

    // Minimal, self-contained context manager for history loading and previewing.
    // Provides a stub analyzer and a simple project root based on the current working directory.
    private static final class MinimalContextManager implements IContextManager {
        private final IProject project;
        private final IAnalyzer analyzer = new IAnalyzer() {};

        MinimalContextManager() {
            Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            this.project = new SimpleProject(root);
        }

        @Override
        public IProject getProject() {
            return project;
        }

        @Override
        public IAnalyzer getAnalyzer() {
            return analyzer;
        }

        @Override
        public IAnalyzer getAnalyzerUninterrupted() {
            return analyzer;
        }

        @Override
        public ProjectFile toFile(String relName) {
            Path root = project.getRoot();
            Path p = Path.of(relName).toAbsolutePath().normalize();
            Path rel;
            if (p.startsWith(root)) {
                rel = root.relativize(p);
            } else {
                Path candidate = Path.of(relName);
                rel = candidate.isAbsolute() ? candidate.getFileName() : candidate.normalize();
            }
            return new ProjectFile(root, rel);
        }

        private static final class SimpleProject implements IProject {
            private final Path root;

            SimpleProject(Path root) {
                this.root = root;
            }

            @Override
            public Path getRoot() {
                return root;
            }

            @Override
            public Set<ProjectFile> getAllFiles() {
                return Set.of();
            }

            @Override
            public void close() {
                // nothing to do
            }
        }
    }
}
