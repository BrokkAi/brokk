package ai.brokk.gui.components;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.dialogs.PreviewImagePanel;
import ai.brokk.gui.dialogs.PreviewTextPanel;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;

/**
 * A panel with a list of tabs on the right and content on the left.
 * Uses JList for tab selection with full control over alignment.
 */
public class PreviewTabbedPane extends JPanel implements ThemeAware {
    private final Chrome chrome;
    private GuiTheme guiTheme;
    private final Consumer<String> titleChangedCallback;
    private final Runnable emptyCallback;

    private final Map<ProjectFile, Component> fileToTabMap = new HashMap<>();
    private final Map<Component, ContextFragment> tabToFragmentMap = new HashMap<>();

    private final DefaultListModel<TabEntry> listModel = new DefaultListModel<>();
    private final JList<TabEntry> tabList;
    private final JPanel contentPanel;
    private final CardLayout cardLayout;
    private final Timer refreshTimer;
    private int cardCounter = 0;

    private record TabEntry(
            String title,
            JComponent component,
            @Nullable ProjectFile fileKey,
            @Nullable ContextFragment fragmentKey,
            String cardName) {}

    public PreviewTabbedPane(
            Chrome chrome, GuiTheme guiTheme, Consumer<String> titleChangedCallback, Runnable emptyCallback) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.guiTheme = guiTheme;
        this.titleChangedCallback = titleChangedCallback;
        this.emptyCallback = emptyCallback;

        // Content panel with CardLayout
        cardLayout = new CardLayout();
        contentPanel = new JPanel(cardLayout);

        // Tab list on the right
        tabList = new JList<>(listModel);
        tabList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tabList.setCellRenderer(new TabCellRenderer());
        tabList.setFixedCellWidth(200);
        tabList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSelection();
            }
        });

        // Handle close button clicks
        tabList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = tabList.locationToIndex(e.getPoint());
                if (index >= 0) {
                    var cellBounds = tabList.getCellBounds(index, index);
                    if (cellBounds != null && cellBounds.contains(e.getPoint())) {
                        // Check if click is in the close button area (right side)
                        int closeButtonX = cellBounds.x + cellBounds.width - TabCellRenderer.CLOSE_WIDTH;
                        if (e.getX() >= closeButtonX) {
                            var entry = listModel.get(index);
                            closeTab(entry.component(), entry.fileKey());
                        }
                    }
                }
            }
        });

        var listScrollPane = new JScrollPane(tabList);
        listScrollPane.setPreferredSize(new Dimension(200, 0));
        listScrollPane.setBorder(BorderFactory.createEmptyBorder());
        listScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        add(contentPanel, BorderLayout.CENTER);
        add(listScrollPane, BorderLayout.EAST);

        // Timer to refresh dirty indicators
        refreshTimer = new Timer(500, e -> tabList.repaint());
        refreshTimer.setRepeats(true);
        refreshTimer.start();
    }

    @Override
    public void removeNotify() {
        refreshTimer.stop();
        super.removeNotify();
    }

    private void updateSelection() {
        var selected = tabList.getSelectedValue();
        if (selected != null) {
            cardLayout.show(contentPanel, selected.cardName());
            titleChangedCallback.accept(selected.title());
        } else {
            titleChangedCallback.accept("");
        }
    }

    public void addOrSelectTab(
            String title, JComponent panel, @Nullable ProjectFile fileKey, @Nullable ContextFragment fragmentKey) {
        String tabTitle = title.startsWith("Preview: ") ? title.substring(9) : title;

        // Check for existing tab by fileKey
        if (fileKey != null) {
            Component existingTab = fileToTabMap.get(fileKey);
            if (existingTab != null) {
                int index = findIndexByComponent(existingTab);
                if (index >= 0 && tryReplaceOrSelectTab(index, panel, tabTitle, fileKey, fragmentKey)) {
                    return;
                }
            }
        }

        // Check for existing tab by fragmentKey
        if (fragmentKey != null) {
            for (var entry : tabToFragmentMap.entrySet()) {
                if (fragmentsMatch(entry.getValue(), fragmentKey)) {
                    int index = findIndexByComponent(entry.getKey());
                    if (index >= 0 && tryReplaceOrSelectTab(index, panel, tabTitle, fileKey, fragmentKey)) {
                        return;
                    }
                }
            }
        }

        // Add new tab
        String cardName = "card" + cardCounter++;
        contentPanel.add(panel, cardName);

        var entry = new TabEntry(tabTitle, panel, fileKey, fragmentKey, cardName);
        listModel.addElement(entry);

        if (fileKey != null) fileToTabMap.put(fileKey, panel);
        if (fragmentKey != null) tabToFragmentMap.put(panel, fragmentKey);

        if (panel instanceof ThemeAware themeAware) {
            themeAware.applyTheme(guiTheme);
        }

        tabList.setSelectedIndex(listModel.size() - 1);
    }

    private int findIndexByComponent(Component comp) {
        for (int i = 0; i < listModel.size(); i++) {
            if (listModel.get(i).component() == comp) {
                return i;
            }
        }
        return -1;
    }

    private boolean fragmentsMatch(ContextFragment existing, ContextFragment candidate) {
        if (existing instanceof ContextFragments.StringFragment sfExisting
                && candidate instanceof ContextFragments.StringFragment sfCandidate) {
            return Objects.equals(
                    sfExisting.text().renderNowOrNull(), sfCandidate.text().renderNowOrNull());
        }
        return existing.hasSameSource(candidate);
    }

    private boolean tryReplaceOrSelectTab(
            int index,
            JComponent panel,
            String tabTitle,
            @Nullable ProjectFile fileKey,
            @Nullable ContextFragment fragmentKey) {
        var oldEntry = listModel.get(index);
        if (oldEntry.component() instanceof PreviewTextPanel existingPanel && !existingPanel.confirmClose()) {
            tabList.setSelectedIndex(index);
            return true;
        }

        // Remove old component from content panel
        contentPanel.remove(oldEntry.component());
        tabToFragmentMap.remove(oldEntry.component());

        // Add new component
        String cardName = "card" + cardCounter++;
        contentPanel.add(panel, cardName);

        var newEntry = new TabEntry(tabTitle, panel, fileKey, fragmentKey, cardName);
        listModel.set(index, newEntry);

        if (fileKey != null) fileToTabMap.put(fileKey, panel);
        if (fragmentKey != null) tabToFragmentMap.put(panel, fragmentKey);

        if (panel instanceof ThemeAware themeAware) {
            themeAware.applyTheme(guiTheme);
        }

        // Ensure the new card is shown (selection listener may not fire if index unchanged)
        cardLayout.show(contentPanel, cardName);
        tabList.setSelectedIndex(index);
        return true;
    }

    public void closeTab(Component panel, @Nullable ProjectFile fileKey) {
        if (panel instanceof PreviewTextPanel textPanel && !textPanel.confirmClose()) return;

        if (fileKey != null) {
            fileToTabMap.remove(fileKey);
            chrome.getPreviewManager().getProjectFileToPreviewWindow().remove(fileKey);
        }
        tabToFragmentMap.remove(panel);

        int index = findIndexByComponent(panel);
        if (index >= 0) {
            var entry = listModel.get(index);
            contentPanel.remove(entry.component());
            listModel.remove(index);

            // Select adjacent tab
            if (listModel.size() > 0) {
                tabList.setSelectedIndex(Math.min(index, listModel.size() - 1));
            }
        }

        if (listModel.isEmpty()) emptyCallback.run();
    }

    public void updateTabTitle(JComponent panel, String newTitle) {
        int index = findIndexByComponent(panel);
        if (index >= 0) {
            String tabTitle = newTitle.startsWith("Preview: ") ? newTitle.substring(9) : newTitle;
            var oldEntry = listModel.get(index);
            var newEntry = new TabEntry(
                    tabTitle, oldEntry.component(), oldEntry.fileKey(), oldEntry.fragmentKey(), oldEntry.cardName());
            listModel.set(index, newEntry);
        }
    }

    public void replaceTabComponent(JComponent oldComponent, JComponent newComponent, String title) {
        int index = findIndexByComponent(oldComponent);
        if (index >= 0) {
            var oldEntry = listModel.get(index);
            ProjectFile fileKey = oldEntry.fileKey();
            ContextFragment fragmentKey = tabToFragmentMap.remove(oldComponent);
            if (fileKey != null) fileToTabMap.remove(fileKey);

            contentPanel.remove(oldComponent);
            String cardName = "card" + cardCounter++;
            contentPanel.add(newComponent, cardName);

            String tabTitle = title.startsWith("Preview: ") ? title.substring(9) : title;
            var newEntry = new TabEntry(tabTitle, newComponent, fileKey, fragmentKey, cardName);
            listModel.set(index, newEntry);

            if (fileKey != null) fileToTabMap.put(fileKey, newComponent);
            if (fragmentKey != null) tabToFragmentMap.put(newComponent, fragmentKey);

            if (newComponent instanceof ThemeAware themeAware) {
                themeAware.applyTheme(guiTheme);
            }
            // Ensure the new card is shown (selection listener may not fire if index unchanged)
            cardLayout.show(contentPanel, cardName);
            tabList.setSelectedIndex(index);
        }
    }

    public void refreshTabsForFile(ProjectFile file) {
        for (int i = 0; i < listModel.size(); i++) {
            var entry = listModel.get(i);
            if (entry.component() instanceof PreviewTextPanel panel) {
                if (file.equals(panel.getFile())) panel.refreshFromDisk();
            } else if (entry.component() instanceof PreviewImagePanel imagePanel) {
                if (file.equals(imagePanel.getFile())) imagePanel.refreshFromDisk();
            }
        }
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        this.guiTheme = guiTheme;
        for (int i = 0; i < listModel.size(); i++) {
            var entry = listModel.get(i);
            if (entry.component() instanceof ThemeAware themeAware) {
                themeAware.applyTheme(guiTheme);
            }
        }
        SwingUtilities.updateComponentTreeUI(this);
    }

    public Map<ProjectFile, Component> getFileToTabMap() {
        return fileToTabMap;
    }

    /**
     * Refreshes the tab list display (e.g., to update dirty indicators).
     */
    public void refreshTabList() {
        tabList.repaint();
    }

    /**
     * Finds the first tab component matching the predicate.
     */
    public Optional<Component> findTab(Predicate<Component> predicate) {
        for (int i = 0; i < listModel.size(); i++) {
            var comp = listModel.get(i).component();
            if (predicate.test(comp)) {
                return Optional.of(comp);
            }
        }
        return Optional.empty();
    }

    /**
     * Selects the tab containing the given component.
     * @return true if the component was found and selected
     */
    public boolean selectTab(Component comp) {
        int index = findIndexByComponent(comp);
        if (index >= 0) {
            tabList.setSelectedIndex(index);
            return true;
        }
        return false;
    }

    /**
     * Closes the currently selected tab.
     */
    public void closeSelectedTab() {
        int selectedIndex = tabList.getSelectedIndex();
        if (selectedIndex >= 0) {
            var entry = listModel.get(selectedIndex);
            closeTab(entry.component(), entry.fileKey());
        }
    }

    /**
     * Custom cell renderer for left-aligned tab entries with close button.
     * Truncates long file names with ellipsis.
     */
    private static class TabCellRenderer extends JPanel implements ListCellRenderer<TabEntry> {
        private static final int FIXED_WIDTH = 200;
        private static final int DIRTY_WIDTH = 16;
        private static final int CLOSE_WIDTH = 20;
        private static final int PADDING = 8 + 4 + 4 + 8; // left border + gaps + right border

        private final JLabel dirtyLabel = new JLabel();
        private final JLabel titleLabel = new JLabel();
        private final JLabel closeLabel = new JLabel("x");

        TabCellRenderer() {
            setLayout(new BorderLayout(4, 0));
            setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));

            // Dirty indicator on the left
            dirtyLabel.setPreferredSize(new Dimension(DIRTY_WIDTH, 16));
            add(dirtyLabel, BorderLayout.WEST);

            titleLabel.setHorizontalAlignment(SwingConstants.LEFT);
            add(titleLabel, BorderLayout.CENTER);

            closeLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            closeLabel.setHorizontalAlignment(SwingConstants.CENTER);
            closeLabel.setPreferredSize(new Dimension(CLOSE_WIDTH, 20));
            add(closeLabel, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends TabEntry> list, TabEntry value, int index, boolean isSelected, boolean cellHasFocus) {
            // Truncate title if needed
            int availableWidth = FIXED_WIDTH - DIRTY_WIDTH - CLOSE_WIDTH - PADDING;
            titleLabel.setText(
                    truncateText(value.title(), titleLabel.getFontMetrics(titleLabel.getFont()), availableWidth));

            // Check for unsaved changes and set icon
            boolean dirty = value.component() instanceof PreviewTextPanel ptp && ptp.hasUnsavedChanges();
            dirtyLabel.setIcon(dirty ? createDirtyIcon() : null);
            dirtyLabel.setText("");

            if (isSelected) {
                setBackground(list.getSelectionBackground());
                titleLabel.setForeground(list.getSelectionForeground());
                closeLabel.setForeground(list.getSelectionForeground());
            } else {
                setBackground(list.getBackground());
                titleLabel.setForeground(list.getForeground());
                closeLabel.setForeground(list.getForeground());
            }

            setOpaque(true);
            return this;
        }

        private String truncateText(String text, FontMetrics fm, int maxWidth) {
            if (fm.stringWidth(text) <= maxWidth) {
                return text;
            }
            String ellipsis = "...";
            int ellipsisWidth = fm.stringWidth(ellipsis);
            int len = text.length();
            while (len > 0 && fm.stringWidth(text.substring(0, len)) + ellipsisWidth > maxWidth) {
                len--;
            }
            return len > 0 ? text.substring(0, len) + ellipsis : ellipsis;
        }

        private Icon createDirtyIcon() {
            return new Icon() {
                @Override
                public void paintIcon(Component c, Graphics g, int x, int y) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                    // Small red asterisk (same as FileTreePanel.createDirtyStatusIcon)
                    int cx = x + 8;
                    int cy = y + 8;
                    int arm = 3;

                    g2.setColor(Color.RED);
                    g2.drawLine(cx - arm, cy, cx + arm, cy);
                    g2.drawLine(cx, cy - arm, cx, cy + arm);
                    g2.drawLine(cx - arm, cy - arm, cx + arm, cy + arm);
                    g2.drawLine(cx - arm, cy + arm, cx + arm, cy - arm);

                    g2.dispose();
                }

                @Override
                public int getIconWidth() {
                    return 16;
                }

                @Override
                public int getIconHeight() {
                    return 16;
                }
            };
        }
    }
}
