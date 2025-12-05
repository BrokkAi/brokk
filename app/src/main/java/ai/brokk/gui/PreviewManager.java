package ai.brokk.gui;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ComputedSubscription;
import ai.brokk.context.ContextFragment;
import ai.brokk.gui.dialogs.PreviewFrame;
import ai.brokk.gui.dialogs.PreviewImagePanel;
import ai.brokk.gui.dialogs.PreviewTextPanel;
import ai.brokk.gui.mop.MarkdownOutputPanel;
import ai.brokk.gui.mop.MarkdownOutputPool;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.search.GenericSearchBar;
import ai.brokk.gui.search.MarkdownSearchableComponent;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.project.MainProject;
import ai.brokk.util.FileUtil;
import ai.brokk.util.ImageUtil;
import ai.brokk.util.Messages;
import com.formdev.flatlaf.util.SystemInfo;
import dev.langchain4j.data.message.ChatMessage;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    // Shared frame for all PreviewTextPanel tabs
    @Nullable
    private PreviewFrame previewFrame;

    @Nullable
    private final Rectangle dependenciesDialogBounds = null;

    private final ContextManager cm;

    public PreviewManager(Chrome chrome) {
        this.chrome = chrome;
        this.cm = chrome.getContextManager();
    }

    Map<ProjectFile, JFrame> getProjectFileToPreviewWindow() {
        return projectFileToPreviewWindow;
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
        var contentPanel = new SearchableContentPanel(componentsWithChatBackground, markdownPanels);
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
        // Delegate to tabbed frame - it handles file tracking internally
        showPreviewTextPanelInTabbedFrame(title, contentComponent);
    }

    /**
     * Shows a component in the shared tabbed preview frame. Lazily creates the frame and
     * ensures bounds, min size, and theme are applied. Selects existing tab by file when possible.
     */
    public void showPreviewTextPanelInTabbedFrame(String title, JComponent panel) {
        SwingUtilities.invokeLater(() -> {
            // Create frame if it doesn't exist or was disposed
            if (previewFrame == null || !previewFrame.isDisplayable()) {
                previewFrame = new PreviewFrame(chrome, cm, chrome.getTheme());

                // Set bounds using same logic as regular preview windows
                var project = cm.getProject();
                var storedBounds = project.getPreviewWindowBounds();
                if (storedBounds.width > 0 && storedBounds.height > 0) {
                    previewFrame.setBounds(storedBounds);
                    if (!chrome.isPositionOnScreen(storedBounds.x, storedBounds.y)) {
                        previewFrame.setLocationRelativeTo(chrome.getFrame());
                    }
                } else {
                    previewFrame.setSize(800, 600);
                    previewFrame.setLocationRelativeTo(chrome.getFrame());
                }

                // Set minimum size
                previewFrame.setMinimumSize(new Dimension(700, 200));

                // Add listener to save bounds
                previewFrame.addComponentListener(new ComponentAdapter() {
                    @Override
                    public void componentMoved(ComponentEvent e) {
                        cm.getProject().savePreviewWindowBounds(previewFrame);
                    }

                    @Override
                    public void componentResized(ComponentEvent e) {
                        cm.getProject().savePreviewWindowBounds(previewFrame);
                    }
                });

                // Apply theme
                previewFrame.applyTheme(chrome.getTheme());
            }

            // Compute file mapping if applicable
            ProjectFile file = null;
            if (panel instanceof PreviewTextPanel ptp) {
                file = ptp.getFile();
            } else if (panel instanceof PreviewImagePanel pip) {
                var bf = pip.getFile();
                if (bf instanceof ProjectFile pf) {
                    file = pf;
                }
            }

            // Add or select tab
            previewFrame.addOrSelectTab(title, panel, file);

            // Track file mapping if applicable
            if (file != null) {
                projectFileToPreviewWindow.put(file, previewFrame);
            }

            // Show and focus
            previewFrame.setVisible(true);
            previewFrame.toFront();
            previewFrame.requestFocus();

            // macOS focus workaround
            if (SystemInfo.isMacOS) {
                previewFrame.setAlwaysOnTop(true);
                previewFrame.setAlwaysOnTop(false);
            }
        });
    }

    /**
     * Called when the shared PreviewTextFrame is being disposed to clear our reference.
     */
    public void clearPreviewTextFrame() {
        previewFrame = null;
    }

    /**
     * Closes all active preview windows and clears the tracking maps. Useful for cleanup or when switching projects.
     */
    public void closeAllPreviewWindows() {
        if (previewFrame != null && previewFrame.isDisplayable()) {
            previewFrame.dispose();
        }
        previewFrame = null;

        projectFileToPreviewWindow.clear();
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
                    // Check if it's the shared PreviewTextFrame
                    if (previewFrame == this.previewFrame && this.previewFrame != null) {
                        // Refresh all tabs for this file in the tabbed frame
                        this.previewFrame.refreshTabsForFile(file);
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

    /**
     * Centralized method to open a preview for a specific ProjectFile. Reads the file, determines syntax, creates
     * PreviewTextPanel, and shows the frame.
     *
     * @param pf The ProjectFile to preview.
     */
    public void previewFile(ProjectFile pf) {
        previewFile(pf, -1);
    }

    /**
     * Centralized method to open a preview for a specific ProjectFile at a specified line position.
     *
     * @param pf        The ProjectFile to preview.
     * @param startLine The line number (0-based) to position the caret at, or -1 to use default positioning.
     */
    public void previewFile(ProjectFile pf, int startLine) {
        assert SwingUtilities.isEventDispatchThread() : "Preview must be initiated on EDT";

        try {
            // 1. Read file content
            var content = pf.read();
            if (content.isEmpty()) {
                chrome.toolError("Unable to read file for preview");
                return;
            }

            // 2. Deduce syntax style
            var syntax = pf.getSyntaxStyle();

            // 3. Build the PTP with custom positioning
            var panel = new PreviewTextPanel(cm, pf, content.get(), syntax, chrome.getTheme(), null);

            // 4. Show in frame first
            showPreviewFrame("Preview: " + pf, panel);

            // 5. Position the caret at the specified line if provided, after showing the frame
            if (startLine >= 0) {
                // Convert line number to character offset using actual (CRLF/LF/CR) separators
                var text = content.get();
                var lineStarts = FileUtil.computeLineStarts(text);
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (startLine < lineStarts.length) {
                            var charOffset = lineStarts[startLine];
                            panel.setCaretPositionAndCenter(charOffset);
                        } else {
                            logger.warn(
                                    "Start line {} exceeds file length {} for {}",
                                    startLine,
                                    lineStarts.length,
                                    pf.absPath());
                        }
                    } catch (Exception e) {
                        logger.warn(
                                "Failed to position caret at line {} for {}: {}",
                                startLine,
                                pf.absPath(),
                                e.getMessage());
                        // Fall back to default positioning (beginning of file)
                    }
                });
            }

        } catch (Exception ex) {
            chrome.toolError("Error opening file preview: " + ex.getMessage());
            logger.error("Unexpected error opening preview for file {}", pf.absPath(), ex);
        }
    }

    /**
     * Opens an in-place preview of a context fragment without updating history.
     * Uses ComputedSubscription.bind() to handle async loading and updates cleanly.
     *
     * <p><b>Unified Entry Point:</b> All fragment preview requests should route through this method.</p>
     */
    public void openFragmentPreview(ContextFragment fragment) {
        try {
            // Resolve initial title - use placeholder if not yet computed
            String descNow = fragment.description().renderNowOrNull();
            final String initialTitle =
                    (descNow != null && !descNow.isBlank()) ? "Preview: " + descNow : "Preview: Loading...";

            // Output fragments: content is synchronous (entries() returns immediately)
            if (fragment.getType().isOutput() && fragment instanceof ContextFragment.OutputFragment of) {
                previewOutputFragment(of, initialTitle);
                return;
            }

            // Image fragments
            if (!fragment.isText()) {
                if (fragment.getType() == ContextFragment.FragmentType.PASTE_IMAGE
                        && fragment instanceof ContextFragment.AnonymousImageFragment pif) {
                    previewAnonymousImage(pif, initialTitle);
                    return;
                }
                if (fragment.getType() == ContextFragment.FragmentType.IMAGE_FILE
                        && fragment instanceof ContextFragment.ImageFileFragment iff) {
                    var imagePanel = new PreviewImagePanel(iff.file());
                    showPreviewFrame(initialTitle, imagePanel);
                    bindTitleUpdate(iff, imagePanel, initialTitle);
                    return;
                }
            }

            // Path fragments (file-backed)
            if (fragment instanceof ContextFragment.PathFragment pf) {
                previewPathFragment(pf, initialTitle);
                return;
            }

            // String-backed fragments (virtual text with sync content)
            if (fragment instanceof ContextFragment.StringFragment sf) {
                previewStringFragment(sf, initialTitle);
                return;
            }

            // Other computed fragments
            previewVirtualFragment(fragment, initialTitle);

        } catch (Exception ex) {
            chrome.toolError("Error opening preview: " + ex.getMessage());
        }
    }

    /**
     * Updates the title of a preview window if the initial title was a placeholder.
     */
    private void updateTitleIfNeeded(String initialTitle, JComponent contentPanel, @Nullable String newTitle) {
        if (initialTitle.endsWith("Loading...") && newTitle != null && !newTitle.isBlank()) {
            updatePreviewWindowTitle(initialTitle, contentPanel, "Preview: " + newTitle);
        }
    }

    /**
     * Binds a fragment to a panel for title-only updates.
     * Used when content is already loaded but title may still be computing.
     */
    private void bindTitleUpdate(ContextFragment fragment, JComponent panel, String initialTitle) {
        if (initialTitle.endsWith("Loading...")) {
            ComputedSubscription.bind(fragment, panel, () -> {
                String desc = fragment.description().renderNowOrNull();
                if (desc != null && !desc.isBlank()) {
                    SwingUtilities.invokeLater(() -> updateTitleIfNeeded(initialTitle, panel, desc));
                }
            });
        }
    }

    /**
     * Preview for output fragments (content is synchronous, only title may need async update).
     */
    private void previewOutputFragment(ContextFragment.OutputFragment of, String initialTitle) {
        var combinedMessages = new ArrayList<ChatMessage>();
        for (ai.brokk.TaskEntry entry : of.entries()) {
            if (entry.isCompressed()) {
                combinedMessages.add(Messages.customSystem(Objects.toString(entry.summary(), "Summary not available")));
            } else {
                combinedMessages.addAll(castNonNull(entry.log()).messages());
            }
        }
        var markdownPanel = MarkdownOutputPool.instance().borrow();
        markdownPanel.withContextForLookups(cm, chrome);
        markdownPanel.setText(combinedMessages);
        JPanel previewContentPanel = createSearchableContentPanel(List.of(markdownPanel), null, false);
        showPreviewFrame(initialTitle, previewContentPanel);

        // Bind for title updates if needed
        if (of instanceof ContextFragment cf) {
            bindTitleUpdate(cf, previewContentPanel, initialTitle);
        }
    }

    /**
     * Preview for anonymous pasted images. Uses bind() for async image and title updates.
     */
    private void previewAnonymousImage(ContextFragment.AnonymousImageFragment pif, String initialTitle) {
        var imagePanel = new PreviewImagePanel(null);
        showPreviewFrame(initialTitle, imagePanel);

        ComputedSubscription.bind(pif, imagePanel, () -> {
            SwingUtilities.invokeLater(() -> {
                // Update image if available
                var futureBytes = pif.imageBytes();
                if (futureBytes != null) {
                    byte[] bytes = futureBytes.renderNowOrNull();
                    if (bytes != null) {
                        try {
                            var img = ImageUtil.bytesToImage(bytes);
                            imagePanel.setImage(img);
                            imagePanel.revalidate();
                            imagePanel.repaint();
                        } catch (IOException e) {
                            logger.error("Unable to convert bytes to image for fragment {}", pif.id(), e);
                        }
                    }
                }
                // Update title
                String desc = pif.description().renderNowOrNull();
                if (desc != null && !desc.isBlank()) {
                    updateTitleIfNeeded(initialTitle, imagePanel, desc);
                }
            });
        });
    }

    /**
     * Preview for path fragments (ProjectFile or ExternalFile). Uses bind() for async content and title.
     */
    private void previewPathFragment(ContextFragment.PathFragment pf, String initialTitle) {
        var brokkFile = pf.file();

        ProjectFile projectFile = (brokkFile instanceof ProjectFile p) ? p : null;

        // Get initial style for syntax highlighting
        String initialStyle = SyntaxConstants.SYNTAX_STYLE_NONE;
        if (projectFile != null) {
            initialStyle = projectFile.getSyntaxStyle();
        } else if (brokkFile instanceof ExternalFile ef) {
            initialStyle = ef.getSyntaxStyle();
        }

        var panel = new PreviewTextPanel(cm, projectFile, "Loading...", initialStyle, chrome.getTheme(), pf);
        showPreviewFrame(initialTitle, panel);

        final String fallbackStyle = initialStyle;
        ComputedSubscription.bind(pf, panel, () -> {
            SwingUtilities.invokeLater(() -> {
                String text = pf.text().renderNowOrNull();
                String style = pf.syntaxStyle().renderNowOrNull();
                String desc = pf.description().renderNowOrNull();

                if (text != null) {
                    panel.setContentAndStyle(text, style != null ? style : fallbackStyle);
                }
                if (desc != null && !desc.isBlank()) {
                    updateTitleIfNeeded(initialTitle, panel, desc);
                }
            });
        });
    }

    /**
     * Preview for StringFragment (has synchronous content via previewText/previewSyntaxStyle).
     */
    private void previewStringFragment(ContextFragment.StringFragment sf, String initialTitle) {
        String previewText = sf.previewText();
        String previewStyle = sf.previewSyntaxStyle();

        if (SyntaxConstants.SYNTAX_STYLE_MARKDOWN.equals(previewStyle)) {
            var markdownPanel = MarkdownOutputPool.instance().borrow();
            markdownPanel.updateTheme(MainProject.getTheme());
            markdownPanel.setText(List.of(Messages.customSystem(previewText)));

            JPanel previewContentPanel = createSearchableContentPanel(List.of(markdownPanel), null, false);
            showPreviewFrame(initialTitle, previewContentPanel);
            bindTitleUpdate(sf, previewContentPanel, initialTitle);
        } else {
            var previewPanel = new PreviewTextPanel(cm, null, previewText, previewStyle, chrome.getTheme(), sf);
            showPreviewFrame(initialTitle, previewPanel);
            bindTitleUpdate(sf, previewPanel, initialTitle);
        }
    }

    /**
     * Preview for computed fragments. Uses bind() to update content, style, and title as they become available.
     */
    private void previewVirtualFragment(ContextFragment cf, String initialTitle) {
        String styleNow = cf.syntaxStyle().renderNowOrNull();
        String textNow = cf.text().renderNowOrNull();

        String initialStyle = (styleNow != null) ? styleNow : SyntaxConstants.SYNTAX_STYLE_NONE;
        String initialText = (textNow != null) ? textNow : "Loading...";

        // If content is already available and is markdown, show markdown panel directly
        if (textNow != null && SyntaxConstants.SYNTAX_STYLE_MARKDOWN.equals(styleNow)) {
            JPanel contentPanel = renderMarkdownContent(textNow);
            showPreviewFrame(initialTitle, contentPanel);
            bindTitleUpdate(cf, contentPanel, initialTitle);
            return;
        }

        // Create text panel (may need style/content updates via bind)
        var panel = new PreviewTextPanel(cm, null, initialText, initialStyle, chrome.getTheme(), cf);
        showPreviewFrame(initialTitle, panel);

        ComputedSubscription.bind(cf, panel, () -> {
            SwingUtilities.invokeLater(() -> {
                String text = cf.text().renderNowOrNull();
                String style = cf.syntaxStyle().renderNowOrNull();
                String desc = cf.description().renderNowOrNull();

                if (text != null) {
                    String effectiveStyle = (style != null) ? style : SyntaxConstants.SYNTAX_STYLE_NONE;

                    // Check if we need to switch to markdown rendering
                    if (SyntaxConstants.SYNTAX_STYLE_MARKDOWN.equals(effectiveStyle)) {
                        JPanel markdownPanel = renderMarkdownContent(text);
                        String title = (desc != null && !desc.isBlank()) ? "Preview: " + desc : initialTitle;
                        replaceTabContent(panel, markdownPanel, title);
                    } else {
                        panel.setContentAndStyle(text, effectiveStyle);
                    }
                }
                if (desc != null && !desc.isBlank()) {
                    updateTitleIfNeeded(initialTitle, panel, desc);
                }
            });
        });
    }

    /**
     * Renders markdown content and wraps it in a searchable preview panel.
     */
    private JPanel renderMarkdownContent(String text) {
        var markdownPanel = MarkdownOutputPool.instance().borrow();
        markdownPanel.updateTheme(MainProject.getTheme());
        markdownPanel.setText(List.of(Messages.customSystem(text)));
        return createSearchableContentPanel(List.of(markdownPanel), null, false);
    }

    /**
     * Replaces an existing tab's content with a new component.
     * Used when placeholder content needs to be replaced with a different component type (e.g., text -> markdown).
     */
    private void replaceTabContent(JComponent oldComponent, JComponent newComponent, String title) {
        SwingUtilities.invokeLater(() -> {
            if (previewFrame != null && previewFrame.isDisplayable()) {
                previewFrame.replaceTabComponent(oldComponent, newComponent, title);
            }
        });
    }

    /**
     * Update the window title for an existing preview in a safe EDT manner and repaint.
     */
    private void updatePreviewWindowTitle(String initialTitle, JComponent contentComponent, String newTitle) {
        SwingUtilities.invokeLater(() -> {
            try {
                if (previewFrame != null && previewFrame.isDisplayable()) {
                    previewFrame.updateTabTitle(contentComponent, newTitle);
                }
            } catch (Exception ex) {
                logger.debug("Unable to update preview window title", ex);
            }
        });
    }

    /**
     * Panel wrapper that supports theming for markdown-based previews with search.
     */
    private static class SearchableContentPanel extends JPanel implements ThemeAware {
        private final List<JComponent> componentsWithChatBackground;
        private final List<MarkdownOutputPanel> markdownPanels;

        SearchableContentPanel(
                List<JComponent> componentsWithChatBackground, List<MarkdownOutputPanel> markdownPanels) {
            super(new BorderLayout());
            this.componentsWithChatBackground = componentsWithChatBackground;
            this.markdownPanels = markdownPanels;
        }

        List<MarkdownOutputPanel> getMarkdownPanels() {
            return markdownPanels;
        }

        @Override
        public void applyTheme(GuiTheme guiTheme) {
            Color newBackgroundColor = ThemeColors.getColor(guiTheme.isDarkTheme(), "chat_background");
            componentsWithChatBackground.forEach(c -> c.setBackground(newBackgroundColor));
            SwingUtilities.updateComponentTreeUI(this);
        }
    }
}
