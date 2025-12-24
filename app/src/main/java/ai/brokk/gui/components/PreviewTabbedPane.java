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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
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
    private int cardCounter = 0;

    private record TabEntry(String title, JComponent component, @Nullable ProjectFile fileKey,
                            @Nullable ContextFragment fragmentKey, String cardName) {}

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
                    if (cellBounds != null) {
                        // Check if click is in the close button area (right side)
                        int closeButtonWidth = 24;
                        int closeButtonX = cellBounds.x + cellBounds.width - closeButtonWidth;
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

        add(contentPanel, BorderLayout.CENTER);
        add(listScrollPane, BorderLayout.EAST);

        // Timer to refresh dirty indicators
        var refreshTimer = new Timer(500, e -> tabList.repaint());
        refreshTimer.setRepeats(true);
        refreshTimer.start();
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
        String cardName = "card" + (cardCounter++);
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
            int index, JComponent panel, String tabTitle,
            @Nullable ProjectFile fileKey, @Nullable ContextFragment fragmentKey) {
        var oldEntry = listModel.get(index);
        if (oldEntry.component() instanceof PreviewTextPanel existingPanel && !existingPanel.confirmClose()) {
            tabList.setSelectedIndex(index);
            return true;
        }

        // Remove old component from content panel
        contentPanel.remove(oldEntry.component());
        tabToFragmentMap.remove(oldEntry.component());

        // Add new component
        String cardName = "card" + (cardCounter++);
        contentPanel.add(panel, cardName);

        var newEntry = new TabEntry(tabTitle, panel, fileKey, fragmentKey, cardName);
        listModel.set(index, newEntry);

        if (fileKey != null) fileToTabMap.put(fileKey, panel);
        if (fragmentKey != null) tabToFragmentMap.put(panel, fragmentKey);

        if (panel instanceof ThemeAware themeAware) {
            themeAware.applyTheme(guiTheme);
        }

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
            var newEntry = new TabEntry(tabTitle, oldEntry.component(), oldEntry.fileKey(),
                                        oldEntry.fragmentKey(), oldEntry.cardName());
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
            String cardName = "card" + (cardCounter++);
            contentPanel.add(newComponent, cardName);

            String tabTitle = title.startsWith("Preview: ") ? title.substring(9) : title;
            var newEntry = new TabEntry(tabTitle, newComponent, fileKey, fragmentKey, cardName);
            listModel.set(index, newEntry);

            if (fileKey != null) fileToTabMap.put(fileKey, newComponent);
            if (fragmentKey != null) tabToFragmentMap.put(newComponent, fragmentKey);

            if (newComponent instanceof ThemeAware themeAware) {
                themeAware.applyTheme(guiTheme);
            }
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

    // Compatibility methods for code that expects JTabbedPane
    public int getTabCount() {
        return listModel.size();
    }

    public Component getComponentAt(int index) {
        return listModel.get(index).component();
    }

    public int indexOfComponent(Component comp) {
        return findIndexByComponent(comp);
    }

    public void setSelectedIndex(int index) {
        if (index >= 0 && index < listModel.size()) {
            tabList.setSelectedIndex(index);
        }
    }

    public int getSelectedIndex() {
        return tabList.getSelectedIndex();
    }

    /**
     * Custom cell renderer for left-aligned tab entries with close button
     */
    private class TabCellRenderer extends JPanel implements ListCellRenderer<TabEntry> {
        private final JLabel dirtyLabel = new JLabel();
        private final JLabel titleLabel = new JLabel();
        private final JLabel closeLabel = new JLabel("x");

        TabCellRenderer() {
            setLayout(new BorderLayout(4, 0));
            setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));

            // Dirty indicator on the left
            dirtyLabel.setPreferredSize(new Dimension(16, 16));
            add(dirtyLabel, BorderLayout.WEST);

            titleLabel.setHorizontalAlignment(SwingConstants.LEFT);
            add(titleLabel, BorderLayout.CENTER);

            closeLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            closeLabel.setHorizontalAlignment(SwingConstants.CENTER);
            closeLabel.setPreferredSize(new Dimension(20, 20));
            add(closeLabel, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends TabEntry> list, TabEntry value, int index,
                boolean isSelected, boolean cellHasFocus) {
            titleLabel.setText(value.title());

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
