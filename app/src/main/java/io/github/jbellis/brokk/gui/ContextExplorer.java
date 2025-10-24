package io.github.jbellis.brokk.gui;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.SessionManager;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.context.FrozenFragment;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
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
    private final Path sessionsDir;

    // Pane 1: Sessions Table (Left) - sortable with 3 columns
    private final SessionTableModel sessionsTableModel = new SessionTableModel();
    private final JTable sessionsTable = new JTable(sessionsTableModel);
    private final TableRowSorter<SessionTableModel> sessionsSorter = new TableRowSorter<>(sessionsTableModel);

    @Nullable
    private UUID selectedSessionId = null;

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
        this.sessionsDir = sessionsDir;
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
        // --- Pane 1: Sessions Table ---
        sessionsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sessionsTable.setBorder(new EmptyBorder(5, 5, 5, 5));
        sessionsTable.setRowSorter(sessionsSorter);
        // Configure sorting: numeric for task count, by distinct type count for types
        sessionsSorter.setComparator(1, Comparator.comparingInt(o -> (Integer) o));
        sessionsSorter.setComparator(0, Comparator.comparing(String::valueOf));
        sessionsSorter.setComparator(2, Comparator.comparingInt(o -> {
            String types = (String) o;
            return types.isEmpty()
                    ? 0
                    : (int) types.chars().filter(ch -> ch == ',').count() + 1;
        }));
        sessionsTable.getTableHeader().setReorderingAllowed(false);
        // Column widths
        var colModel = sessionsTable.getColumnModel();
        colModel.getColumn(0).setPreferredWidth(140); // ID
        colModel.getColumn(1).setPreferredWidth(80); // Task Count
        colModel.getColumn(2).setPreferredWidth(220); // Types
        var leftScroll = new JScrollPane(sessionsTable);
        leftScroll.setMinimumSize(new Dimension(300, 0));

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
        mainSplit.setResizeWeight(0.25); // Sessions table takes 25%, rightSplit takes 75%

        var toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        var exportButton = new MaterialButton("Export");
        exportButton.addActionListener(e -> exportSession());
        toolbar.add(exportButton);

        add(toolbar, BorderLayout.NORTH);
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
                    sessionsTableModel.setSessions(sessions);
                    if (!sessions.isEmpty()) {
                        // Select first by default on view
                        sessionsTable.setRowSelectionInterval(0, 0);
                        var modelRow = sessionsTable.convertRowIndexToModel(0);
                        var info = sessionsTableModel.getRow(modelRow).info();
                        selectedSessionId = info.id();
                        loadSessionHistory(info);
                    }
                    // Load stats (task count + types) lazily
                    preloadSessionStats(sessions);
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

    private void preloadSessionStats(List<SessionManager.SessionInfo> sessions) {
        if (sessions.isEmpty()) {
            return;
        }
        new SwingWorker<Void, SessionStats>() {
            @Override
            protected Void doInBackground() {
                for (var info : sessions) {
                    try {
                        var stats = computeStats(info);
                        publish(stats);
                    } catch (Exception e) {
                        logger.warn("Failed to compute stats for session {}", info.id(), e);
                        // Publish with zeros to avoid perpetual "Loading..."
                        publish(new SessionStats(info.id(), 0, ""));
                    }
                }
                return null;
            }

            @Override
            protected void process(List<SessionStats> chunks) {
                for (var s : chunks) {
                    sessionsTableModel.updateStats(s.id(), s.taskCount(), s.types());
                    // Maintain selection even if sorting changes row positions
                    if (selectedSessionId != null && selectedSessionId.equals(s.id())) {
                        selectSessionById(selectedSessionId);
                    }
                }
            }

            @Override
            protected void done() {
                // nothing else to do
            }
        }.execute();
    }

    private SessionStats computeStats(SessionManager.SessionInfo info) throws IOException {
        var ch = sessionManager.loadHistory(info.id(), contextManager);
        if (ch == null) {
            throw new IOException("Unable to load history for session " + info.name() + " (ID: " + info.id() + ")");
        }
        int taskCount = ch.getHistory().size();
        Set<String> typeNames = new TreeSet<>();
        for (var ctx : ch.getHistory()) {
            // Fragments
            ctx.allFragments().forEach(f -> {
                try {
                    typeNames.add(f.getType().name());
                } catch (Exception e) {
                    logger.debug("Error getting type for fragment {} in context {}", f.id(), ctx.id(), e);
                }
            });
            // Parsed output if available
            var parsed = ctx.getParsedOutput();
            if (parsed != null) {
                try {
                    typeNames.add(parsed.getType().name());
                } catch (Exception e) {
                    logger.debug("Error getting type for parsed output in context {}", ctx.id(), e);
                }
            }
        }
        var types = typeNames.stream().collect(Collectors.joining(", "));
        return new SessionStats(info.id(), taskCount, types);
    }

    private void wireEvents() {
        // Sessions table selection
        sessionsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int viewRow = sessionsTable.getSelectedRow();
                if (viewRow >= 0) {
                    int modelRow = sessionsTable.convertRowIndexToModel(viewRow);
                    var row = sessionsTableModel.getRow(modelRow);
                    selectedSessionId = row.info().id();
                    loadSessionHistory(row.info());
                } else {
                    selectedSessionId = null;
                    tableModel.clear();
                    clearPreview();
                }
            }
        });

        // Contexts/fragments table selection
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

    private void selectSessionById(@Nullable UUID id) {
        if (id == null) return;
        int modelIndex = sessionsTableModel.indexOf(id);
        if (modelIndex < 0) return;
        int viewIndex = sessionsTable.convertRowIndexToView(modelIndex);
        if (viewIndex >= 0) {
            sessionsTable.getSelectionModel().setSelectionInterval(viewIndex, viewIndex);
        }
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
                        if (fragment instanceof FrozenFragment ff) {
                            imageContent = ff.image();
                        } else if (fragment instanceof ContextFragment.ImageFileFragment ifd) {
                            imageContent = ifd.image();
                        } else if (fragment instanceof ContextFragment.AnonymousImageFragment aif) {
                            imageContent = aif.image();
                        } else {
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
                    imageLabel.setText("");
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

    private void exportSession() {
        if (selectedSessionId == null) {
            JOptionPane.showMessageDialog(this, "No session selected.", "Export Error", JOptionPane.WARNING_MESSAGE);
            return;
        }

        final var sessionIdLocal = selectedSessionId;

        new SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() throws Exception {
                var rows = tableModel.rows;
                List<ContextExport> exports = new ArrayList<>();
                ContextExport current = null;

                for (var row : rows) {
                    if (row instanceof HeaderRow h) {
                        if (current != null) {
                            exports.add(current);
                        }
                        current = new ContextExport(
                                h.index(),
                                h.contextId().toString(),
                                h.action(),
                                h.historyEntries(),
                                h.historyLines(),
                                new ArrayList<>());
                    } else if (row instanceof FragmentRow fr && current != null) {
                        var f = fr.fragment();
                        current.fragments()
                                .add(new FragmentExport(
                                        f.id(),
                                        f.getType().name(),
                                        f.shortDescription(),
                                        fr.lineCount(),
                                        f.syntaxStyle()));
                    }
                }
                if (current != null) {
                    exports.add(current);
                }

                // Ensure deterministic ordering: sort fragments by id (lexicographically)
                for (var e : exports) {
                    e.fragments().sort(Comparator.comparing(FragmentExport::id));
                }

                var mapper = new ObjectMapper();
                var jsonl = exports.stream()
                        .map(e -> {
                            try {
                                return mapper.writeValueAsString(e);
                            } catch (Exception ex) {
                                throw new RuntimeException(ex);
                            }
                        })
                        .collect(Collectors.joining("\n"));

                var exportPath = sessionsDir.resolve(sessionIdLocal.toString() + ".jsonl");
                Files.writeString(exportPath, jsonl, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                logger.info("Exported session {} to {}", sessionIdLocal, exportPath);
                return exportPath;
            }

            @Override
            protected void done() {
                try {
                    Path exportPath = get();
                    JOptionPane.showMessageDialog(
                            ContextExplorer.this,
                            "Session exported to:\n" + exportPath,
                            "Export Success",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("Export interrupted", e);
                    JOptionPane.showMessageDialog(
                            ContextExplorer.this, "Export interrupted.", "Export Error", JOptionPane.ERROR_MESSAGE);
                } catch (ExecutionException e) {
                    logger.error("Failed to export session", e);
                    var cause = e.getCause();
                    var msg = (cause != null && cause.getMessage() != null) ? cause.getMessage() : e.toString();
                    JOptionPane.showMessageDialog(
                            ContextExplorer.this,
                            "Failed to export session: " + msg,
                            "Export Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private record ContextExport(
            int contextIndex,
            String contextId,
            String action,
            int historyEntries,
            int historyLines,
            List<FragmentExport> fragments) {}

    private record FragmentExport(String id, String type, String shortDescription, int lineCount, String syntaxStyle) {}

    public static void main(String[] args) {
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
                System.exit(0);
            }
        });
    }

    private static @Nullable Path chooseSessionsDir() {
        var chooser = new JFileChooser();
        chooser.setDialogTitle("Select Brokk sessions directory");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setCurrentDirectory(new File(System.getProperty("user.home")));
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
            "Type", "Context Info", "Fragment ID", "FragType", "Description", "Lines", "Syntax"
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
                    default -> "";
                };
            } else {
                var fr = (FragmentRow) row;
                var f = fr.fragment();
                return switch (columnIndex) {
                    case 0 -> "Fragment";
                    case 1 -> shortContext(fr.contextId());
                    case 2 -> f.id();
                    case 3 -> f.getType().name();
                    case 4 -> f.shortDescription();
                    case 5 -> fr.lineCount();
                    case 6 -> f.syntaxStyle();
                    default -> "";
                };
            }
        }

        private String shortContext(UUID id) {
            String s = id.toString();
            return s.substring(0, 8) + "...";
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
                c.setBackground(new Color(230, 230, 230));
                if (c instanceof JComponent jc) {
                    if (isSelected) {
                        jc.setBorder(UIManager.getBorder("List.focusCellHighlightBorder"));
                    } else {
                        jc.setBorder(null);
                    }
                }
            } else {
                c.setFont(c.getFont().deriveFont(Font.PLAIN));
                c.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
                c.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
            }

            if (c instanceof JComponent jc) {
                String tip = value.toString();
                jc.setToolTipText(!tip.isEmpty() ? tip : null);
            }

            return c;
        }
    }

    // --- Sessions table model and helpers ---

    private record SessionRow(SessionManager.SessionInfo info, int taskCount, String types) {}

    private record SessionStats(UUID id, int taskCount, String types) {}

    private static final class SessionTableModel extends AbstractTableModel {
        private final List<SessionRow> rows = new ArrayList<>();
        private static final String[] COLUMN_NAMES = {"ID", "Task Count", "Types"};

        public void setSessions(List<SessionManager.SessionInfo> sessions) {
            rows.clear();
            // Initialize with placeholders until stats are computed
            for (var s : sessions) {
                rows.add(new SessionRow(s, 0, ""));
            }
            fireTableDataChanged();
        }

        public int indexOf(UUID id) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).info().id().equals(id)) {
                    return i;
                }
            }
            return -1;
        }

        public void updateStats(UUID id, int taskCount, String types) {
            int idx = indexOf(id);
            if (idx >= 0) {
                var old = rows.get(idx);
                rows.set(idx, new SessionRow(old.info(), taskCount, types));
                fireTableRowsUpdated(idx, idx);
            }
        }

        public SessionRow getRow(int modelRow) {
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
        public Class<?> getColumnClass(int columnIndex) {
            return switch (columnIndex) {
                case 0 -> String.class;
                case 1 -> Integer.class;
                case 2 -> String.class;
                default -> Object.class;
            };
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            var row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.info().id().toString();
                case 1 -> row.taskCount();
                case 2 -> row.types();
                default -> "";
            };
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
