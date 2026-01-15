package ai.brokk.gui;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.ContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ComputedSubscription;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
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
import ai.brokk.project.MainProject;
import ai.brokk.util.FileUtil;
import ai.brokk.util.GlobalUiSettings;
import ai.brokk.util.ImageUtil;
import ai.brokk.util.Messages;
import com.formdev.flatlaf.util.SystemInfo;
import dev.langchain4j.data.message.ChatMessage;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import javax.swing.JRootPane;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

/**
 * Manages all preview windows (text, images, fragments) and shared preview state.
 * Chrome delegates preview-related operations to this class.
 */
public class PreviewManager {
    private static final Logger logger = LogManager.getLogger(PreviewManager.class);

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

    private final ContextManager cm;

    public PreviewManager(Chrome chrome) {
        this.chrome = chrome;
        this.cm = chrome.getContextManager();
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

    private void ensurePreviewFrame() {
        if (previewFrame != null && previewFrame.isDisplayable()) {
            return;
        }

        previewFrame = new DetachableTabFrame("Preview", new JPanel(new BorderLayout()), () -> {
            chrome.getRightPanel().redockPreview();
            chrome.clearPreviewFrame();
        });
        var frame = previewFrame;

        var project = cm.getProject();
        var storedBounds = project.getPreviewWindowBounds();
        if (storedBounds.width > 0 && storedBounds.height > 0) {
            frame.setBounds(storedBounds);
            if (!chrome.isPositionOnScreen(storedBounds.x, storedBounds.y)) {
                frame.setLocationRelativeTo(chrome.getFrame());
            }
        } else {
            frame.setSize(800, 600);
            frame.setLocationRelativeTo(chrome.getFrame());
        }

        frame.setMinimumSize(new Dimension(700, 200));
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                cm.getProject().savePreviewWindowBounds(frame);
            }

            @Override
            public void componentResized(ComponentEvent e) {
                cm.getProject().savePreviewWindowBounds(frame);
            }
        });

        frame.applyTheme(chrome.getTheme());
    }

    /**
     * Extracts the ProjectFile key from a panel or fragment for file-based deduplication.
     */
    private @Nullable ProjectFile extractFileKey(JComponent panel, @Nullable ContextFragment fragment) {
        // Try panel first
        if (panel instanceof PreviewTextPanel ptp) {
            return ptp.getFile();
        }
        if (panel instanceof PreviewImagePanel pip) {
            var bf = pip.getFile();
            if (bf instanceof ProjectFile pf) {
                return pf;
            }
        }
        // Try fragment
        if (fragment instanceof ContextFragments.PathFragment pathFragment) {
            var bf = pathFragment.file();
            if (bf instanceof ProjectFile pf) {
                return pf;
            }
        }
        return null;
    }

    /**
     * Shows a diff panel in the shared preview interface.
     */
    public void showDiffInTab(
            String title, BrokkDiffPanel panel, List<BufferSource> leftSources, List<BufferSource> rightSources) {
        // No-op
    }

    private boolean tryRaiseExistingDiffWindow(List<BufferSource> leftSources, List<BufferSource> rightSources) {
        for (Frame frame : Frame.getFrames()) {
            if (!frame.isDisplayable()) {
                continue;
            }

            var brokkPanel = findBrokkDiffPanel(frame);
            if (brokkPanel != null && brokkPanel.matchesContent(leftSources, rightSources)) {
                var window = SwingUtilities.getWindowAncestor(brokkPanel);
                if (window != null) {
                    raiseWindow(window);
                    return true;
                }
            }
        }
        return false;
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

    private String computeInitialTitle(ContextFragment fragment) {
        String descNow = fragment.shortDescription().renderNowOrNull();
        return (descNow != null && !descNow.isBlank()) ? "Preview: " + descNow : "Preview: Loading...";
    }

    private void showOutputPreview(ContextFragments.OutputFragment of, String initialTitle) {
        var combinedMessages = new ArrayList<ChatMessage>();
        for (TaskEntry entry : of.entries()) {
            if (entry.isCompressed()) {
                combinedMessages.add(Messages.customSystem(Objects.toString(entry.summary(), "Summary not available")));
            } else {
                combinedMessages.addAll(castNonNull(entry.log()).messages());
            }
        }

        var markdownPanel = new MarkdownOutputPanel();
        markdownPanel.setContextForLookups(cm, chrome);
        markdownPanel.setMessages(combinedMessages);

        JPanel contentPanel = createSearchableContentPanel(List.of(markdownPanel), null, false);
        ContextFragment fragment = (of instanceof ContextFragment cf) ? cf : null;
        showPreviewFrame(initialTitle, contentPanel, fragment);

        if (fragment != null) {
            bindTitleUpdate(fragment, contentPanel, initialTitle);
        }
    }

    private void showImagePreview(ContextFragment fragment, String initialTitle) {
        if (fragment instanceof ContextFragments.AnonymousImageFragment pif) {
            var imagePanel = new PreviewImagePanel(chrome, null);
            showPreviewFrame(initialTitle, imagePanel, pif);

            ComputedSubscription.bind(
                    pif,
                    imagePanel,
                    () -> SwingUtilities.invokeLater(() -> {
                        updateImagePanel(pif, imagePanel);
                        updateTitleIfNeeded(
                                initialTitle, imagePanel, pif.shortDescription().renderNowOrNull());
                    }));
        } else if (fragment instanceof ContextFragments.ImageFileFragment iff) {
            var imagePanel = new PreviewImagePanel(chrome, iff.file());
            showPreviewFrame(initialTitle, imagePanel, iff);
            bindTitleUpdate(iff, imagePanel, initialTitle);
        }
    }

    private void updateImagePanel(ContextFragments.AnonymousImageFragment pif, PreviewImagePanel imagePanel) {
        var futureBytes = pif.imageBytes();
        if (futureBytes == null) {
            return;
        }
        byte[] bytes = futureBytes.renderNowOrNull();
        if (bytes == null) {
            return;
        }
        try {
            imagePanel.setImage(ImageUtil.bytesToImage(bytes));
            imagePanel.revalidate();
            imagePanel.repaint();
        } catch (IOException e) {
            logger.error("Unable to convert bytes to image for fragment {}", pif.id(), e);
        }
    }

    private void showStringPreview(ContextFragments.StringFragment sf, String initialTitle) {
        String text = sf.previewText();
        String style = sf.previewSyntaxStyle();

        JComponent panel;
        if (SyntaxConstants.SYNTAX_STYLE_MARKDOWN.equals(style)) {
            var markdownPanel = new MarkdownOutputPanel();
            markdownPanel.updateTheme(MainProject.getTheme());
            markdownPanel.setContextForLookups(cm, chrome);
            markdownPanel.setStaticDocument(text);
            panel = createSearchableContentPanel(List.of(markdownPanel), null, false);
        } else {
            panel = new PreviewTextPanel(chrome, cm, null, text, style, chrome.getTheme(), sf);
        }

        showPreviewFrame(initialTitle, panel, sf);
        bindTitleUpdate(sf, panel, initialTitle);
    }

    /**
     * Unified preview for PathFragment and other computed fragments.
     * Uses bind() to update content, style, and title as they become available.
     */
    private void showAsyncTextPreview(ContextFragment fragment, String initialTitle) {
        String textNow = fragment.text().renderNowOrNull();
        String styleNow = fragment.syntaxStyle().renderNowOrNull();

        // If markdown content is already available, render it directly
        if (textNow != null && SyntaxConstants.SYNTAX_STYLE_MARKDOWN.equals(styleNow)) {
            JPanel contentPanel = renderMarkdownContent(textNow);
            showPreviewFrame(initialTitle, contentPanel, fragment);
            bindTitleUpdate(fragment, contentPanel, initialTitle);
            return;
        }

        // Bind to file only when viewing the latest (live) context
        boolean isLive = cm.isLive();

        // Determine initial values - use file-based style detection for path fragments
        String initialText = (textNow != null) ? textNow : "Loading...";
        String initialStyle = (styleNow != null) ? styleNow : guessInitialStyle(fragment);

        // Use the same ProjectFile for the placeholder panel only when viewing the live context.
        // This enables auto-refresh and live editing in the preview panel for the current file.
        // For history snapshots, pass null to prevent auto-refresh and editing, ensuring the preview remains static.
        ProjectFile projectFile = isLive ? extractProjectFile(fragment) : null;

        var panel =
                new PreviewTextPanel(chrome, cm, projectFile, initialText, initialStyle, chrome.getTheme(), fragment);
        showPreviewFrame(initialTitle, panel, fragment);

        final String fallbackStyle = initialStyle;
        ComputedSubscription.bind(
                fragment,
                panel,
                () -> SwingUtilities.invokeLater(() -> {
                    String text = fragment.text().renderNowOrNull();
                    String style = fragment.syntaxStyle().renderNowOrNull();
                    String desc = fragment.shortDescription().renderNowOrNull();

                    if (text != null) {
                        String effectiveStyle = (style != null) ? style : fallbackStyle;

                        if (SyntaxConstants.SYNTAX_STYLE_MARKDOWN.equals(effectiveStyle)) {
                            JPanel markdownPanel = renderMarkdownContent(text);
                            String title = (desc != null && !desc.isBlank()) ? "Preview: " + desc : initialTitle;
                            replaceTabContent(panel, markdownPanel, title);
                        } else {
                            panel.setContentAndStyle(text, effectiveStyle);
                        }
                    }

                    updateTitleIfNeeded(initialTitle, panel, desc);
                }));
    }

    private String guessInitialStyle(ContextFragment fragment) {
        if (fragment instanceof ContextFragments.PathFragment pf) {
            var brokkFile = pf.file();
            if (brokkFile instanceof ProjectFile p) {
                return p.getSyntaxStyle();
            } else if (brokkFile instanceof ExternalFile ef) {
                return ef.getSyntaxStyle();
            }
        }
        return SyntaxConstants.SYNTAX_STYLE_NONE;
    }

    private @Nullable ProjectFile extractProjectFile(ContextFragment fragment) {
        if (fragment instanceof ContextFragments.PathFragment pf && pf.file() instanceof ProjectFile p) {
            return p;
        }
        return null;
    }

    private void updateTitleIfNeeded(String initialTitle, JComponent panel, @Nullable String newTitle) {
        if (initialTitle.endsWith("Loading...") && newTitle != null && !newTitle.isBlank()) {
            updatePreviewWindowTitle(panel, "Preview: " + newTitle);
        }
    }

    private void bindTitleUpdate(ContextFragment fragment, JComponent panel, String initialTitle) {
        if (initialTitle.endsWith("Loading...")) {
            ComputedSubscription.bind(fragment, panel, () -> {
                String desc = fragment.shortDescription().renderNowOrNull();
                if (desc != null && !desc.isBlank()) {
                    SwingUtilities.invokeLater(() -> updateTitleIfNeeded(initialTitle, panel, desc));
                }
            });
        }
    }

    /**
     * Renders markdown content and wraps it in a searchable preview panel.
     */
    private JPanel renderMarkdownContent(String text) {
        var markdownPanel = new MarkdownOutputPanel();
        markdownPanel.updateTheme(MainProject.getTheme());
        markdownPanel.setContextForLookups(cm, chrome);
        markdownPanel.setStaticDocument(text);
        return createSearchableContentPanel(List.of(markdownPanel), null, false);
    }

    /**
     * Replaces an existing preview's content with a new component.
     */
    private void replaceTabContent(JComponent oldComponent, JComponent newComponent, String title) {
        // No-op
    }

    /**
     * Update the window title for an existing preview in a safe EDT manner and repaint.
     */
    private void updatePreviewWindowTitle(JComponent contentComponent, String newTitle) {
        // No-op: tab titles removed
    }

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
