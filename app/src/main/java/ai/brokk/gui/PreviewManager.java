package ai.brokk.gui;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.difftool.ui.BrokkDiffPanel;
import ai.brokk.difftool.ui.BufferSource;
import ai.brokk.gui.dialogs.DetachableTabFrame;
import ai.brokk.gui.dialogs.PreviewImagePanel;
import ai.brokk.gui.dialogs.PreviewTextPanel;
import ai.brokk.gui.mop.MarkdownOutputPanel;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.search.GenericSearchBar;
import ai.brokk.gui.search.MarkdownSearchableComponent;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import org.jetbrains.annotations.Nullable;

/**
 * Manages all preview windows (text, images, fragments) and shared preview state.
 * Chrome delegates preview-related operations to this class.
 */
public class PreviewManager {
    private final Chrome chrome;

    // Track preview windows by ProjectFile for refresh on file changes
    private final Map<ProjectFile, JFrame> projectFileToPreviewWindow = new ConcurrentHashMap<>();

    /** Raise the given window to the front and give it focus. */
    public static void raiseWindow(Window window) {
        SwingUtilities.invokeLater(() -> {
            // Bring window to front
            window.toFront();

            // Request focus
            window.requestFocus();

            // On some platforms, also need to set visible again
            if (!window.isVisible()) {
                window.setVisible(true);
            }

            // Make sure it's not minimized (iconified)
            if (window instanceof Frame frame && frame.getState() == Frame.ICONIFIED) {
                frame.setState(Frame.NORMAL);
            }
        });
    }

    public Map<ProjectFile, JFrame> getProjectFileToPreviewWindow() {
        return projectFileToPreviewWindow;
    }

    // Shared frame for all PreviewTextPanel tabs
    @Nullable
    private DetachableTabFrame previewFrame;

    public PreviewManager(Chrome chrome) {
        this.chrome = chrome;
    }

    /**
     * Creates a searchable content panel with a MarkdownOutputPanel and integrated search bar. This is shared
     * functionality used by both preview windows and detached output windows.
     *
     * @param markdownPanels List of MarkdownOutputPanel instances to make searchable
     * @param toolbarPanel   Optional panel to add to the right of the search bar
     * @return A JPanel containing the search bar, optional toolbar, and content
     */
    public static JPanel createSearchableContentPanel(
            List<MarkdownOutputPanel> markdownPanels, @Nullable JPanel toolbarPanel, boolean wrapInScrollPane) {
        if (markdownPanels.isEmpty()) {
            return new JPanel(); // Return empty panel if no content
        }

        // If single panel, create a scroll pane for it if requested
        JComponent contentComponent;
        var componentsWithChatBackground = new ArrayList<JComponent>();
        if (markdownPanels.size() == 1) {
            if (wrapInScrollPane) {
                var scrollPane = new JScrollPane(markdownPanels.getFirst());
                scrollPane.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
                scrollPane.getVerticalScrollBar().setUnitIncrement(16);
                contentComponent = scrollPane;
            } else {
                contentComponent = markdownPanels.getFirst();
            }
        } else {
            // Multiple panels - create container with BoxLayout
            var messagesContainer = new JPanel();
            messagesContainer.setLayout(new BoxLayout(messagesContainer, BoxLayout.Y_AXIS));
            componentsWithChatBackground.add(messagesContainer);

            for (MarkdownOutputPanel panel : markdownPanels) {
                messagesContainer.add(panel);
            }

            if (wrapInScrollPane) {
                var scrollPane = new JScrollPane(messagesContainer);
                scrollPane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
                scrollPane.getVerticalScrollBar().setUnitIncrement(16);
                contentComponent = scrollPane;
            } else {
                contentComponent = messagesContainer;
            }
        }

        // Create main content panel to hold search bar and content
        var contentPanel = new SearchableContentPanel(componentsWithChatBackground);
        componentsWithChatBackground.add(contentPanel);

        // Create searchable component adapter and generic search bar
        var searchableComponent = new MarkdownSearchableComponent(markdownPanels);
        var searchBar = new GenericSearchBar(searchableComponent);
        componentsWithChatBackground.add(searchBar);

        // Create top panel with search bar and optional toolbar
        JPanel topPanel;
        if (toolbarPanel != null) {
            topPanel = new JPanel(new BorderLayout());
            topPanel.add(searchBar, BorderLayout.CENTER);
            topPanel.add(toolbarPanel, BorderLayout.EAST);
            toolbarPanel.setBackground(markdownPanels.getFirst().getBackground());
            componentsWithChatBackground.add(toolbarPanel);
            topPanel.setBackground(markdownPanels.getFirst().getBackground());
            componentsWithChatBackground.add(topPanel);
        } else {
            topPanel = searchBar;
        }

        componentsWithChatBackground.forEach(
                c -> c.setBackground(markdownPanels.getFirst().getBackground()));

        // Add 5px gap below the top panel
        topPanel.setBorder(new EmptyBorder(0, 0, 5, 0));

        // Add components to content panel
        contentPanel.add(topPanel, BorderLayout.NORTH);
        contentPanel.add(contentComponent, BorderLayout.CENTER);

        // Register Ctrl/Cmd+F to focus search field
        searchBar.registerGlobalShortcuts(contentPanel);

        return contentPanel;
    }

    /**
     * Creates and shows a standard preview in the shared tabbed PreviewFrame.
     * All preview content types are routed into tabs instead of standalone windows.
     *
     * @param title            The title for the tab.
     * @param contentComponent The JComponent to display within the tab.
     */
    public void showPreviewFrame(String title, JComponent contentComponent) {
        // No-op
    }

    /**
     * Creates and shows a preview in the shared tabbed PreviewFrame with fragment-based deduplication.
     *
     * @param title            The title for the tab.
     * @param contentComponent The JComponent to display within the tab.
     * @param fragment         Optional fragment for deduplication via hasSameSource.
     */
    public void showPreviewFrame(String title, JComponent contentComponent, @Nullable ContextFragment fragment) {
        // No-op
    }

    /**
     * Shows a component in the shared preview frame. Lazily creates the frame and
     * ensures bounds, min size, and theme are applied.
     */
    public void showPreviewInTabbedFrame(String title, JComponent panel, @Nullable ContextFragment fragment) {
        // No-op
    }

    /**
     * Extracts the ProjectFile key from a panel or fragment for file-based deduplication.
     */

    /**
     * Shows a diff panel in the shared preview interface.
     */
    public void showDiffInTab(
            String title, BrokkDiffPanel panel, List<BufferSource> leftSources, List<BufferSource> rightSources) {
        // No-op
    }

    @Nullable
    private static BrokkDiffPanel findBrokkDiffPanel(Container root) {
        for (var comp : root.getComponents()) {
            if (comp instanceof BrokkDiffPanel panel) {
                return panel;
            }
            if (comp instanceof Container container) {
                var found = findBrokkDiffPanel(container);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Called when the shared PreviewTextFrame is being disposed to clear our reference.
     */
    public void clearPreviewTextFrame() {
        previewFrame = null;
    }

    /**
     * Refreshes preview windows that display the given files. Called when files change on disk (external edits or
     * internal saves).
     *
     * @param changedFiles The set of files that have changed
     */
    public void refreshPreviewsForFiles(Set<ProjectFile> changedFiles) {
        refreshPreviewsForFiles(changedFiles, null);
    }

    /**
     * Refreshes preview windows that display the given files, optionally excluding a specific frame. Called when files
     * change on disk (external edits or internal saves).
     *
     * @param changedFiles The set of files that have changed
     * @param excludeFrame Optional frame to exclude from refresh (typically the one that just saved)
     */
    public void refreshPreviewsForFiles(Set<ProjectFile> changedFiles, @Nullable JFrame excludeFrame) {
        SwingUtilities.invokeLater(() -> {
            for (ProjectFile file : changedFiles) {
                JFrame previewFrame = projectFileToPreviewWindow.get(file);

                if (previewFrame != null && previewFrame.isDisplayable() && previewFrame != excludeFrame) {
                    if (previewFrame == this.previewFrame) {
                        refreshFrameForFile(this.previewFrame, file);
                    } else if (previewFrame == chrome.getFrame()) {
                        chrome.getRightPanel().refreshPreviewForFile(file);
                    } else {
                        // Legacy standalone windows; keep best-effort refresh
                        Container contentPane = previewFrame.getContentPane();
                        if (contentPane instanceof PreviewImagePanel imagePanel) {
                            imagePanel.refreshFromDisk();
                        } else if (contentPane.getLayout() instanceof BorderLayout bl) {
                            Component centerComponent = bl.getLayoutComponent(BorderLayout.CENTER);
                            if (centerComponent instanceof PreviewImagePanel imagePanel) {
                                imagePanel.refreshFromDisk();
                            }
                        }
                    }
                }
            }
        });
    }

    private void refreshFrameForFile(DetachableTabFrame frame, ProjectFile file) {
        Component content = frame.getContentPane();
        if (content instanceof JRootPane rp) {
            content = rp.getContentPane();
        }
        if (content instanceof Container container && container.getComponentCount() > 0) {
            Component comp = container.getComponent(0);
            if (comp instanceof PreviewTextPanel panel) {
                if (file.equals(panel.getFile())) panel.refreshFromDisk();
            } else if (comp instanceof PreviewImagePanel imagePanel) {
                if (file.equals(imagePanel.getFile())) imagePanel.refreshFromDisk();
            }
        }
    }

    /**
     * Centralized method to open a preview for a specific ProjectFile at a specified line position.
     *
     * @param pf        The ProjectFile to preview.
     * @param startLine The line number (0-based) to position the caret at, or -1 to use default positioning.
     */
    public void previewFile(ProjectFile pf, int startLine) {
        // No-op
    }

    /**
     * Opens an in-place preview of a context fragment without updating history.
     * For live context, binds the preview to the file for auto-refresh and editing.
     * For history snapshots, displays a static preview without disk synchronization.
     *
     * <p><b>Unified Entry Point:</b> All fragment preview requests should route through this method.</p>
     */
    public void openFragmentPreview(ContextFragment fragment) {
        // No-op
    }

    /**
     * Renders markdown content and wraps it in a searchable preview panel.
     */

    /**
     * Panel wrapper that supports theming for markdown-based previews with search.
     */
    private static class SearchableContentPanel extends JPanel implements ThemeAware {
        private final List<JComponent> componentsWithChatBackground;

        SearchableContentPanel(List<JComponent> componentsWithChatBackground) {
            super(new BorderLayout());
            this.componentsWithChatBackground = componentsWithChatBackground;
        }

        @Override
        public void applyTheme(GuiTheme guiTheme) {
            Color newBackgroundColor = ThemeColors.getColor(guiTheme.isDarkTheme(), "chat_background");
            componentsWithChatBackground.forEach(c -> c.setBackground(newBackgroundColor));
            SwingUtilities.updateComponentTreeUI(this);
        }
    }
}
