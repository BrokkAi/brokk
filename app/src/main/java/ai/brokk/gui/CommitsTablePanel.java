package ai.brokk.gui;

import ai.brokk.git.CommitInfo;
import ai.brokk.git.ICommitInfo;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.GitDiffUiUtil;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class CommitsTablePanel extends JPanel implements ThemeAware {

    private final JTable table;
    private final DefaultTableModel tableModel;
    private final List<ICommitInfo> currentCommits = new ArrayList<>();
    private boolean suppressSelectionEvents = false;

    private @javax.annotation.Nullable ActionListener guidedReviewListener;
    private @javax.annotation.Nullable ActionListener captureDiffListener;

    public CommitsTablePanel() {
        setLayout(new BorderLayout());

        tableModel = new DefaultTableModel(new String[] {"Message", "Date"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setTableHeader(null);
        table.setShowGrid(false);
        table.setIntercellSpacing(new java.awt.Dimension(0, 0));

        CommitCellRenderer renderer = new CommitCellRenderer();
        table.getColumnModel().getColumn(0).setCellRenderer(renderer);
        table.getColumnModel().getColumn(1).setCellRenderer(renderer);

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleContextMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handleContextMenu(e);
            }
        });

        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    public void updateCommits(List<CommitInfo> commits, boolean hasUncommitted) {
        suppressSelectionEvents = true;
        try {
            currentCommits.clear();
            tableModel.setRowCount(0);

            if (hasUncommitted) {
                ICommitInfo stub = new ICommitInfo.CommitInfoStub("Uncommitted changes");
                currentCommits.add(stub);
                tableModel.addRow(new Object[] {stub, ""});
            }

            List<CommitInfo> sortedCommits = commits.stream()
                    .sorted((a, b) -> b.date().compareTo(a.date()))
                    .toList();

            for (CommitInfo commit : sortedCommits) {
                currentCommits.add(commit);
                tableModel.addRow(new Object[] {commit, commit});
            }
        } finally {
            suppressSelectionEvents = false;
        }
    }

    public List<String> getSelectedCommitIds() {
        int[] rows = table.getSelectedRows();
        List<String> ids = new ArrayList<>();
        for (int row : rows) {
            ICommitInfo info = currentCommits.get(row);
            if (info instanceof ICommitInfo.CommitInfoStub) {
                ids.add("WORKING");
            } else {
                ids.add(info.id());
            }
        }
        return ids;
    }

    public boolean isSelectionContiguous() {
        int[] rows = table.getSelectedRows();
        if (rows.length <= 1) return true;
        Arrays.sort(rows);
        return GitDiffUiUtil.groupContiguous(rows).size() == 1;
    }

    public boolean isOnlyWorkingSelected() {
        List<String> ids = getSelectedCommitIds();
        return ids.size() == 1 && "WORKING".equals(ids.getFirst());
    }

    public boolean isAllSelected() {
        int selectedCount = table.getSelectedRowCount();
        return selectedCount > 0 && selectedCount == table.getRowCount();
    }

    public void clearSelection() {
        table.clearSelection();
    }

    public void addSelectionListener(Runnable listener) {
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !suppressSelectionEvents) {
                listener.run();
            }
        });
    }

    public void setGuidedReviewActionListener(ActionListener listener) {
        this.guidedReviewListener = listener;
    }

    public void setCaptureDiffActionListener(ActionListener listener) {
        this.captureDiffListener = listener;
    }

    private void handleContextMenu(MouseEvent e) {
        if (!e.isPopupTrigger()) return;

        int row = table.rowAtPoint(e.getPoint());
        if (row != -1 && !table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row);
        }

        if (table.getSelectedRowCount() == 0) return;

        JPopupMenu menu = new JPopupMenu();
        boolean contiguous = isSelectionContiguous();

        JMenuItem reviewItem = new JMenuItem("Guided Review");
        reviewItem.setEnabled(contiguous);
        reviewItem.addActionListener(evt -> {
            if (guidedReviewListener != null) {
                guidedReviewListener.actionPerformed(evt);
            }
        });
        menu.add(reviewItem);

        JMenuItem captureItem = new JMenuItem("Capture Diff");
        captureItem.setEnabled(contiguous);
        captureItem.addActionListener(evt -> {
            if (captureDiffListener != null) {
                captureDiffListener.actionPerformed(evt);
            }
        });
        menu.add(captureItem);

        menu.show(table, e.getX(), e.getY());
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        SwingUtilities.updateComponentTreeUI(this);
    }

    private static class CommitCellRenderer extends DefaultTableCellRenderer {
        private final LocalDate today = LocalDate.now(ZoneId.systemDefault());

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setFont(table.getFont());

            if (value instanceof ICommitInfo info) {
                String message = info.message();
                int newlineIdx = message.indexOf('\n');
                String firstLine = newlineIdx == -1 ? message : message.substring(0, newlineIdx);

                if (column == 0) {
                    setText(truncate(firstLine, 40));
                } else if (column == 1) {
                    if (info.date() != null) {
                        setText(GitDiffUiUtil.formatRelativeDate(info.date(), today));
                    } else {
                        setText("");
                    }
                }

                String tooltip = truncate(firstLine, 120);
                if (!info.author().isEmpty()) {
                    tooltip += " - " + info.author();
                }
                setToolTipText(tooltip);
            } else if (value instanceof String s) {
                setText(s);
                setToolTipText(null);
            }

            setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 5, 0, 5));
            return this;
        }

        private static String truncate(String s, int maxLen) {
            if (s.length() <= maxLen) return s;
            return s.substring(0, Math.max(0, maxLen - 3)) + "...";
        }
    }
}
