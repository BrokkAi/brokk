package ai.brokk.gui.components;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.dialogs.PreviewImagePanel;
import ai.brokk.gui.dialogs.PreviewTextPanel;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * A tabbed pane specialized for previews, with deduplication logic and custom tab components.
 * Can be used inside a standalone frame or embedded in the main UI.
 */
public class PreviewTabbedPane extends JTabbedPane implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(PreviewTabbedPane.class);

    private final Chrome chrome;
    private GuiTheme guiTheme;
    private final Consumer<String> titleChangedCallback;
    private final Runnable emptyCallback;

    private final Map<ProjectFile, Component> fileToTabMap = new HashMap<>();
    private final Map<Component, ContextFragment> tabToFragmentMap = new HashMap<>();

    public PreviewTabbedPane(Chrome chrome, GuiTheme guiTheme, Consumer<String> titleChangedCallback, Runnable emptyCallback) {
        super(JTabbedPane.RIGHT, JTabbedPane.SCROLL_TAB_LAYOUT);
        this.chrome = chrome;
        this.guiTheme = guiTheme;
        this.titleChangedCallback = titleChangedCallback;
        this.emptyCallback = emptyCallback;

        addChangeListener(e -> updateStatus());
    }

    private void updateStatus() {
        int index = getSelectedIndex();
        if (index >= 0) {
            Component tabComponent = getTabComponentAt(index);
            if (tabComponent instanceof JPanel tabPanel) {
                for (Component c : tabPanel.getComponents()) {
                    if (c instanceof JLabel label) {
                        titleChangedCallback.accept(label.getText());
                        return;
                    }
                }
            }
        }
        titleChangedCallback.accept("");
    }

    public void addOrSelectTab(String title, JComponent panel, @Nullable ProjectFile fileKey, @Nullable ContextFragment fragmentKey) {
        String tabTitle = title.startsWith("Preview: ") ? title.substring(9) : title;

        if (fileKey != null) {
            Component existingTab = fileToTabMap.get(fileKey);
            if (existingTab != null) {
                int index = indexOfComponent(existingTab);
                if (index >= 0 && tryReplaceOrSelectTab(index, existingTab, panel, tabTitle, fileKey, fragmentKey)) {
                    return;
                }
            }
        }

        if (fragmentKey != null) {
            for (var entry : tabToFragmentMap.entrySet()) {
                if (fragmentsMatch(entry.getValue(), fragmentKey)) {
                    Component existingTab = entry.getKey();
                    int index = indexOfComponent(existingTab);
                    if (index >= 0 && tryReplaceOrSelectTab(index, existingTab, panel, tabTitle, fileKey, fragmentKey)) {
                        return;
                    }
                }
            }
        }

        addTab(tabTitle, panel);
        int tabIndex = getTabCount() - 1;
        setTabComponentAt(tabIndex, createTabComponent(tabTitle, panel, fileKey));

        if (fileKey != null) fileToTabMap.put(fileKey, panel);
        if (fragmentKey != null) tabToFragmentMap.put(panel, fragmentKey);

        if (panel instanceof ThemeAware themeAware) {
            themeAware.applyTheme(guiTheme);
        }

        setSelectedIndex(tabIndex);
    }

    private boolean fragmentsMatch(ContextFragment existing, ContextFragment candidate) {
        if (existing instanceof ContextFragments.StringFragment sfExisting && candidate instanceof ContextFragments.StringFragment sfCandidate) {
            return Objects.equals(sfExisting.text().renderNowOrNull(), sfCandidate.text().renderNowOrNull());
        }
        return existing.hasSameSource(candidate);
    }

    private boolean tryReplaceOrSelectTab(int index, Component existingTab, JComponent panel, String tabTitle, @Nullable ProjectFile fileKey, @Nullable ContextFragment fragmentKey) {
        if (existingTab instanceof PreviewTextPanel existingPanel && !existingPanel.confirmClose()) {
            setSelectedIndex(index);
            return true;
        }

        tabToFragmentMap.remove(existingTab);
        setComponentAt(index, panel);
        setTabComponentAt(index, createTabComponent(tabTitle, panel, fileKey));

        if (fileKey != null) fileToTabMap.put(fileKey, panel);
        if (fragmentKey != null) tabToFragmentMap.put(panel, fragmentKey);

        if (panel instanceof ThemeAware themeAware) {
            try { themeAware.applyTheme(guiTheme); } catch (Exception ignore) {}
        }

        setSelectedIndex(index);
        return true;
    }

    private JPanel createTabComponent(String title, JComponent panel, @Nullable ProjectFile fileKey) {
        JPanel tabPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        tabPanel.setOpaque(false);
        tabPanel.add(new JLabel(title));

        JButton closeButton = new JButton("×");
        closeButton.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        closeButton.setMargin(new Insets(0, 4, 0, 4));
        closeButton.setContentAreaFilled(false);
        closeButton.setBorderPainted(false);
        closeButton.setFocusPainted(false);
        closeButton.addActionListener(e -> closeTab(panel, fileKey));
        tabPanel.add(closeButton);

        return tabPanel;
    }

    public void closeTab(Component panel, @Nullable ProjectFile fileKey) {
        if (panel instanceof PreviewTextPanel textPanel && !textPanel.confirmClose()) return;

        if (fileKey != null) {
            fileToTabMap.remove(fileKey);
            chrome.getPreviewManager().getProjectFileToPreviewWindow().remove(fileKey);
        }
        tabToFragmentMap.remove(panel);

        int index = indexOfComponent(panel);
        if (index >= 0) remove(index);

        if (getTabCount() == 0) emptyCallback.run();
    }

    public void updateTabTitle(JComponent panel, String newTitle) {
        int index = indexOfComponent(panel);
        if (index >= 0) {
            String tabTitle = newTitle.startsWith("Preview: ") ? newTitle.substring(9) : newTitle;
            Component tabComponent = getTabComponentAt(index);
            if (tabComponent instanceof JPanel tabPanel) {
                for (Component c : tabPanel.getComponents()) {
                    if (c instanceof JLabel label) {
                        label.setText(tabTitle);
                        break;
                    }
                }
            }
        }
    }

    public void replaceTabComponent(JComponent oldComponent, JComponent newComponent, String title) {
        int index = indexOfComponent(oldComponent);
        if (index >= 0) {
            ProjectFile fileKey = null;
            for (var entry : fileToTabMap.entrySet()) {
                if (entry.getValue() == oldComponent) {
                    fileKey = entry.getKey();
                    break;
                }
            }
            ContextFragment fragmentKey = tabToFragmentMap.remove(oldComponent);
            if (fileKey != null) fileToTabMap.remove(fileKey);

            setComponentAt(index, newComponent);
            String tabTitle = title.startsWith("Preview: ") ? title.substring(9) : title;
            setTabComponentAt(index, createTabComponent(tabTitle, newComponent, fileKey));

            if (fileKey != null) fileToTabMap.put(fileKey, newComponent);
            if (fragmentKey != null) tabToFragmentMap.put(newComponent, fragmentKey);

            if (newComponent instanceof ThemeAware themeAware) {
                try { themeAware.applyTheme(guiTheme); } catch (Exception ignore) {}
            }
            setSelectedIndex(index);
        }
    }

    public void refreshTabsForFile(ProjectFile file) {
        for (int i = 0; i < getTabCount(); i++) {
            Component comp = getComponentAt(i);
            if (comp instanceof PreviewTextPanel panel) {
                if (file.equals(panel.getFile())) panel.refreshFromDisk();
            } else if (comp instanceof PreviewImagePanel imagePanel) {
                if (file.equals(imagePanel.getFile())) imagePanel.refreshFromDisk();
            }
        }
    }

    public void clearTracking() {
        for (ProjectFile file : fileToTabMap.keySet()) {
            chrome.getPreviewManager().getProjectFileToPreviewWindow().remove(file);
        }
        fileToTabMap.clear();
        tabToFragmentMap.clear();
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        this.guiTheme = guiTheme;
        for (int i = 0; i < getTabCount(); i++) {
            Component comp = getComponentAt(i);
            if (comp instanceof ThemeAware themeAware) themeAware.applyTheme(guiTheme);
        }
        SwingUtilities.updateComponentTreeUI(this);
    }

    public Map<ProjectFile, Component> getFileToTabMap() { return fileToTabMap; }
}
