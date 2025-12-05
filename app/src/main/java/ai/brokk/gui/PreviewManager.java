package ai.brokk.gui;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.gui.dependencies.DependenciesDrawerPanel;
import ai.brokk.gui.dialogs.PreviewImagePanel;
import ai.brokk.gui.dialogs.PreviewTextFrame;
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
import ai.brokk.util.SwingUtil;
import com.formdev.flatlaf.util.SystemInfo;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
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

    // Track open preview windows for reuse
    private final Map<String, JFrame> activePreviewWindows = new ConcurrentHashMap<>();

    // Track preview windows by ProjectFile for refresh on file changes
    private final Map<ProjectFile, JFrame> projectFileToPreviewWindow = new ConcurrentHashMap<>();

    // Shared frame for all PreviewTextPanel tabs
    @Nullable
    private PreviewTextFrame previewTextFrame;

    @Nullable
    private Rectangle dependenciesDialogBounds = null;

    public PreviewManager(Chrome chrome) {
        this.chrome = chrome;
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
     * Creates and shows a standard preview JFrame for a given component. Handles title, default close operation,
     * loading/saving bounds using the "preview" key, and visibility. Reuses existing preview windows when possible to
     * avoid cluttering the desktop.
     *
     * For PreviewTextPanel instances, routes them to a shared tabbed frame instead of individual windows.
     *
     * @param title            The title for the JFrame.
     * @param contentComponent The JComponent to display within the frame.
     */
    public void showPreviewFrame(String title, JComponent contentComponent) {
        // Special handling for PreviewTextPanel - route to tabbed frame
        if (contentComponent instanceof PreviewTextPanel textPanel) {
            showPreviewTextPanelInTabbedFrame(title, textPanel);
            return;
        }

        // Generate a key for window reuse based on the content type and title
        String windowKey = generatePreviewWindowKey(title, contentComponent);

        // Check if we have an existing window for this content
        JFrame previewFrame = activePreviewWindows.get(windowKey);
        boolean isNewWindow = false;

        // Fallback: if not found via primary key, try alternate key form to reuse placeholder/file-based windows
        if (previewFrame == null || !previewFrame.isDisplayable()) {
            String altKey = computeAlternatePreviewKey(title, contentComponent, windowKey);
            if (altKey != null) {
                JFrame altFrame = activePreviewWindows.get(altKey);
                if (altFrame != null && altFrame.isDisplayable()) {
                    previewFrame = altFrame;
                    windowKey = altKey;
                }
            }
        }

        if (previewFrame == null || !previewFrame.isDisplayable()) {
            // Create new window if none exists or existing one was disposed
            previewFrame = Chrome.newFrame(title);
            activePreviewWindows.put(windowKey, previewFrame);
            isNewWindow = true;

            // Set up new window configuration
            if (SystemInfo.isMacOS && SystemInfo.isMacFullWindowContentSupported) {
                var titleBar = new JPanel(new BorderLayout());
                titleBar.setBorder(new EmptyBorder(4, 80, 4, 0)); // Padding for window controls
                var label = new JLabel(title, javax.swing.SwingConstants.CENTER);
                titleBar.add(label, BorderLayout.CENTER);
                previewFrame.add(titleBar, BorderLayout.NORTH);
            }
            previewFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            previewFrame.setBackground(
                    chrome.getTheme().isDarkTheme() ? javax.swing.UIManager.getColor("chat_background") : Color.WHITE);

            var project = contextManager.getProject();
            boolean isDependencies = contentComponent instanceof DependenciesDrawerPanel;

            if (isDependencies) {
                if (dependenciesDialogBounds != null
                        && dependenciesDialogBounds.width > 0
                        && dependenciesDialogBounds.height > 0) {
                    previewFrame.setBounds(dependenciesDialogBounds);
                    if (!chrome.isPositionOnScreen(dependenciesDialogBounds.x, dependenciesDialogBounds.y)) {
                        previewFrame.setLocationRelativeTo(chrome.getFrame()); // Center if off-screen
                    }
                } else {
                    previewFrame.setSize(800, 500);
                    previewFrame.setLocationRelativeTo(chrome.getFrame());
                }
            } else {
                var storedBounds = project.getPreviewWindowBounds(); // Use preview bounds
                if (storedBounds.width > 0 && storedBounds.height > 0) {
                    previewFrame.setBounds(storedBounds);
                    if (!chrome.isPositionOnScreen(storedBounds.x, storedBounds.y)) {
                        previewFrame.setLocationRelativeTo(chrome.getFrame()); // Center if off-screen
                    }
                } else {
                    previewFrame.setSize(800, 600); // Default size if no bounds saved
                    previewFrame.setLocationRelativeTo(chrome.getFrame()); // Center relative to main window
                }
            }

            // Set a minimum width for preview windows to ensure search controls work properly
            previewFrame.setMinimumSize(new Dimension(700, 200));

            // Add listener to save bounds using the "preview" key
            final JFrame finalFrameForBounds = previewFrame;
            previewFrame.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentMoved(ComponentEvent e) {
                    if (isDependencies) {
                        dependenciesDialogBounds = finalFrameForBounds.getBounds();
                    } else {
                        project.savePreviewWindowBounds(finalFrameForBounds); // Save JFrame bounds
                    }
                }

                @Override
                public void componentResized(ComponentEvent e) {
                    if (isDependencies) {
                        dependenciesDialogBounds = finalFrameForBounds.getBounds();
                    } else {
                        project.savePreviewWindowBounds(finalFrameForBounds); // Save JFrame bounds
                    }
                }
            });
        } else {
            // Reuse existing window - update title and content
            previewFrame.setTitle(title);
            // Only remove the CENTER component to preserve title bar and other layout components
            var contentPane = previewFrame.getContentPane();
            Component centerComponent =
                    ((BorderLayout) contentPane.getLayout()).getLayoutComponent(BorderLayout.CENTER);
            if (centerComponent != null) {
                contentPane.remove(centerComponent);
            }

            // Update title bar label on macOS if it exists
            if (SystemInfo.isMacOS && SystemInfo.isMacFullWindowContentSupported) {
                Component northComponent =
                        ((BorderLayout) contentPane.getLayout()).getLayoutComponent(BorderLayout.NORTH);
                if (northComponent instanceof JPanel titleBar) {
                    Component centerInTitleBar =
                            ((BorderLayout) titleBar.getLayout()).getLayoutComponent(BorderLayout.CENTER);
                    if (centerInTitleBar instanceof JLabel label) {
                        label.setText(title);
                    }
                }
            }
        }

        // Add content component (for both new and reused windows)
        previewFrame.add(contentComponent, BorderLayout.CENTER);

        // Apply theme to ThemeAware components after they're added to the window
        if (contentComponent instanceof ThemeAware themeAware) {
            themeAware.applyTheme(chrome.getTheme());
        }

        // Only use DO_NOTHING_ON_CLOSE for PreviewTextPanel (which has its own confirmation dialog)
        // Other preview types should use DISPOSE_ON_CLOSE for normal close behavior
        if (contentComponent instanceof PreviewTextPanel) {
            previewFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        }

        if (contentComponent instanceof SearchableContentPanel scp) {
            var panels = scp.getMarkdownPanels();
            if (!panels.isEmpty()) {
                previewFrame.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosed(WindowEvent e) {
                        for (var panel : scp.getMarkdownPanels()) {
                            MarkdownOutputPool.instance().giveBack(panel);
                        }
                    }
                });
            }
        }

        // Track ProjectFile mapping if this is a file preview
        ProjectFile projectFile = null;
        if (contentComponent instanceof PreviewImagePanel imagePanel) {
            var brokkFile = imagePanel.getFile();
            if (brokkFile instanceof ProjectFile pf) {
                projectFile = pf;
            }
        }

        if (projectFile != null) {
            projectFileToPreviewWindow.put(projectFile, previewFrame);
        }

        // Add window cleanup listener to remove from tracking maps when window is disposed
        final String finalWindowKey = windowKey;
        final JFrame finalPreviewFrame = previewFrame;
        final ProjectFile finalProjectFile = projectFile;
        if (isNewWindow) {
            previewFrame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    // Remove from tracking maps when window is closed
                    activePreviewWindows.remove(finalWindowKey, finalPreviewFrame);
                    if (finalProjectFile != null) {
                        projectFileToPreviewWindow.remove(finalProjectFile, finalPreviewFrame);
                    }
                }
            });
        }

        // Add ESC key binding to close the window (delegates to windowClosing)
        final JFrame finalFrameForESC = previewFrame;
        JRootPane rootPane = previewFrame.getRootPane();
        var actionMap = rootPane.getActionMap();
        var inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);

        inputMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "closeWindow");
        actionMap.put("closeWindow", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                // Simulate window closing event to trigger the WindowListener logic
                finalFrameForESC.dispatchEvent(new WindowEvent(finalFrameForESC, WindowEvent.WINDOW_CLOSING));
            }
        });

        // Bring window to front and make visible
        previewFrame.setVisible(true);
        previewFrame.toFront();
        previewFrame.requestFocus();
        // On macOS, sometimes need to explicitly request focus
        if (SystemInfo.isMacOS) {
            previewFrame.setAlwaysOnTop(true);
            previewFrame.setAlwaysOnTop(false);
        }
    }

    /**
     * Shows a PreviewTextPanel in the shared tabbed preview frame.
     */
    public void showPreviewTextPanelInTabbedFrame(String title, PreviewTextPanel panel) {
        SwingUtilities.invokeLater(() -> {
            // Create frame if it doesn't exist or was disposed
            if (previewTextFrame == null || !previewTextFrame.isDisplayable()) {
                previewTextFrame = new PreviewTextFrame(chrome, chrome.getContextManager(), chrome.getTheme());

                // Set bounds using same logic as regular preview windows
                var project = chrome.getContextManager().getProject();
                var storedBounds = project.getPreviewWindowBounds();
                if (storedBounds.width > 0 && storedBounds.height > 0) {
                    previewTextFrame.setBounds(storedBounds);
                    if (!chrome.isPositionOnScreen(storedBounds.x, storedBounds.y)) {
                        previewTextFrame.setLocationRelativeTo(chrome.getFrame());
                    }
                } else {
                    previewTextFrame.setSize(800, 600);
                    previewTextFrame.setLocationRelativeTo(chrome.getFrame());
                }

                // Set minimum size
                previewTextFrame.setMinimumSize(new Dimension(700, 200));

                // Add listener to save bounds
                previewTextFrame.addComponentListener(new ComponentAdapter() {
                    @Override
                    public void componentMoved(ComponentEvent e) {
                        project.savePreviewWindowBounds(previewTextFrame);
                    }

                    @Override
                    public void componentResized(ComponentEvent e) {
                        project.savePreviewWindowBounds(previewTextFrame);
                    }
                });

                // Apply theme
                previewTextFrame.applyTheme(chrome.getTheme());
            }

            // Add or select tab
            ProjectFile file = panel.getFile();
            previewTextFrame.addOrSelectTab(title, panel, file);

            // Track file mapping if applicable
            if (file != null) {
                projectFileToPreviewWindow.put(file, previewTextFrame);
            }

            // Show and focus
            previewTextFrame.setVisible(true);
            previewTextFrame.toFront();
            previewTextFrame.requestFocus();

            // macOS focus workaround
            if (SystemInfo.isMacOS) {
                previewTextFrame.setAlwaysOnTop(true);
                previewTextFrame.setAlwaysOnTop(false);
            }
        });
    }

    /**
     * Called when the shared PreviewTextFrame is being disposed to clear our reference.
     */
    public void clearPreviewTextFrame() {
        previewTextFrame = null;
    }

    /**
     * Closes all active preview windows and clears the tracking maps. Useful for cleanup or when switching projects.
     */
    public void closeAllPreviewWindows() {
        if (previewTextFrame != null && previewTextFrame.isDisplayable()) {
            previewTextFrame.dispose();
        }
        previewTextFrame = null;

        for (JFrame frame : activePreviewWindows.values()) {
            if (frame.isDisplayable()) {
                frame.dispose();
            }
        }
        activePreviewWindows.clear();
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
                    if (previewFrame == previewTextFrame && previewTextFrame != null) {
                        // Refresh all tabs for this file in the tabbed frame
                        previewTextFrame.refreshTabsForFile(file);
                    } else {
                        // Handle regular preview windows (non-PTP)
                        Container contentPane = previewFrame.getContentPane();

                        // Refresh based on panel type
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
            var panel = new PreviewTextPanel(chrome.getContextManager(), pf, content.get(), syntax, chrome.getTheme(), null);

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
     * Uses non-blocking computed accessors when available; otherwise renders placeholders and
     * loads the actual values off-EDT, then updates the UI on the EDT.
     *
     * <p><b>Unified Entry Point:</b> All fragment preview requests should route through this method.</p>
     */
    public void openFragmentPreview(ContextFragment fragment) {
        try {
            // Resolve title once and cache it for reuse
            String computedDescNow = fragment.description().renderNowOrNull();
            final String initialTitle = (computedDescNow != null && !computedDescNow.isBlank())
                    ? "Preview: " + computedDescNow
                    : "Preview: Loading...";

            // Output fragments: build immediately (no analyzer calls)
            if (fragment.getType().isOutput() && fragment instanceof ContextFragment.OutputFragment of) {
                previewOutputFragment(of, initialTitle, computedDescNow);
                return;
            }

            // Image fragments: avoid fragment getters on EDT; update image and title async.
            if (!fragment.isText()) {
                if (fragment.getType() == ContextFragment.FragmentType.PASTE_IMAGE
                        && fragment instanceof ContextFragment.AnonymousImageFragment pif) {
                    previewAnonymousImage(pif, initialTitle);
                    return;
                }
                if (fragment.getType() == ContextFragment.FragmentType.IMAGE_FILE
                        && fragment instanceof ContextFragment.ImageFileFragment iff) {
                    SwingUtilities.invokeLater(
                            () -> PreviewImagePanel.showInFrame(chrome.getFrame(), chrome.getContextManager(), iff.file()));
                    return;
                }
            }

            // Live path fragments: load asynchronously to avoid I/O on EDT
            if (fragment instanceof ContextFragment.PathFragment pf) {
                previewPathFragment(pf, initialTitle, computedDescNow);
                return;
            }

            // String-backed fragments (virtual text)
            if (fragment instanceof ContextFragment.StringFragment sf) {
                String previewText = sf.previewText();
                String previewStyle = sf.previewSyntaxStyle();

                if (SyntaxConstants.SYNTAX_STYLE_MARKDOWN.equals(previewStyle)) {
                    var markdownPanel = MarkdownOutputPool.instance().borrow();
                    markdownPanel.updateTheme(MainProject.getTheme());
                    markdownPanel.setText(List.of(Messages.customSystem(previewText)));

                    JPanel previewContentPanel =
                            createSearchableContentPanel(List.of(markdownPanel), null, false);

                    showPreviewFrame(initialTitle, previewContentPanel);
                    updateDescriptionAsync(initialTitle, null, computedDescNow, sf);
                } else {
                    var previewPanel = new PreviewTextPanel(
                            chrome.getContextManager(), null, previewText, previewStyle, chrome.getTheme(), sf);
                    showPreviewFrame(initialTitle, previewPanel);
                    updateDescriptionAsync(initialTitle, previewPanel, computedDescNow, sf);
                }
            } else {
                // Virtual fragment: show placeholder and load in background
                previewVirtualFragment(fragment, initialTitle, computedDescNow);
            }
        } catch (Exception ex) {
            chrome.toolError("Error opening preview: " + ex.getMessage());
        }
    }

    /**
     * Updates the title of a preview window asynchronously if the initial title is a placeholder.
     * Does nothing if the initial title is already finalized (non-blank).
     */
    private void updateTitleIfNeeded(String initialTitle, JComponent contentPanel, @Nullable String newTitle) {
        if (initialTitle.endsWith("Loading...") && newTitle != null && !newTitle.isBlank()) {
            updatePreviewWindowTitle(initialTitle, contentPanel, "Preview: " + newTitle);
        }
    }

    /**
     * Preview for output fragments (non-blocking, built immediately).
     */
    private void previewOutputFragment(
            ContextFragment.OutputFragment of, String initialTitle, @Nullable String computedDescNow) {
        var combinedMessages = new ArrayList<ChatMessage>();
        for (ai.brokk.TaskEntry entry : of.entries()) {
            if (entry.isCompressed()) {
                combinedMessages.add(Messages.customSystem(Objects.toString(entry.summary(), "Summary not available")));
            } else {
                combinedMessages.addAll(castNonNull(entry.log()).messages());
            }
        }
        var markdownPanel = MarkdownOutputPool.instance().borrow();
        markdownPanel.withContextForLookups(chrome.getContextManager(), chrome);
        markdownPanel.setText(combinedMessages);
        JPanel previewContentPanel = createSearchableContentPanel(List.of(markdownPanel), null, false);
        showPreviewFrame(initialTitle, previewContentPanel);

        // Update title asynchronously if needed
        if ((computedDescNow == null || computedDescNow.isBlank()) && of instanceof ContextFragment cf) {
            cf.description().onComplete((description, e) -> {
                if (e != null) {
                    logger.warn("Failed to render computed description for fragment {}", cf.id(), e);
                } else {
                    updateTitleIfNeeded(initialTitle, previewContentPanel, description);
                }
            });
        }
    }

    /**
     * Preview for anonymous pasted images.
     */
    private void previewAnonymousImage(ContextFragment.AnonymousImageFragment pif, String initialTitle) {
        var imagePanel = new PreviewImagePanel(null);
        showPreviewFrame(initialTitle, imagePanel);

        var futureImageBytes = pif.imageBytes();
        if (futureImageBytes != null) {
            futureImageBytes.onComplete((bytes, e) -> {
                if (e != null) {
                    logger.error("Unable to load image bytes for fragment {}", pif.id(), e);
                } else {
                    try {
                        var img = ImageUtil.bytesToImage(bytes);
                        SwingUtilities.invokeLater(() -> {
                            imagePanel.setImage(img);
                            imagePanel.revalidate();
                            imagePanel.repaint();
                        });
                    } catch (IOException ioEx) {
                        logger.error("Unable to convert bytes to image for fragment {}", pif.id(), ioEx);
                    }
                }
            });
        }
        pif.description().onComplete((description, e) -> {
            if (e != null) {
                logger.warn("Failed to render computed description for fragment {}", pif.id(), e);
            } else {
                updateTitleIfNeeded(initialTitle, imagePanel, description);
            }
        });
    }

    /**
     * Preview for path fragments (ProjectFile or ExternalFile).
     */
    private void previewPathFragment(
            ContextFragment.PathFragment pf, String initialTitle, @Nullable String computedDescNow) {
        var brokkFile = pf.file();

        // Use the same ProjectFile for the placeholder panel to ensure the same reuse key
        ProjectFile placeholderFile = (brokkFile instanceof ProjectFile p) ? p : null;

        // Use the best-available syntax style even for the placeholder (helps early highlight)
        String placeholderStyle = SyntaxConstants.SYNTAX_STYLE_NONE;
        if (brokkFile instanceof ProjectFile p) {
            placeholderStyle = p.getSyntaxStyle();
        } else if (brokkFile instanceof ExternalFile ef) {
            placeholderStyle = ef.getSyntaxStyle();
        }

        var placeholder =
                new PreviewTextPanel(chrome.getContextManager(), placeholderFile, "Loading...", placeholderStyle, chrome.getTheme(), pf);
        showPreviewFrame(initialTitle, placeholder);

        if (brokkFile instanceof ProjectFile projectFile) {
            loadAndPreviewFile(projectFile, projectFile.getSyntaxStyle(), initialTitle, pf);
        } else if (brokkFile instanceof ExternalFile externalFile) {
            loadAndPreviewFile(null, externalFile.getSyntaxStyle(), initialTitle, pf);
        }

        updateDescriptionAsync(initialTitle, placeholder, computedDescNow, pf);
    }

    private void loadAndPreviewFile(
            @Nullable ProjectFile projectFile, String style, String initialTitle, ContextFragment fragment) {
        chrome.getContextManager().submitBackgroundTask("Load file preview", () -> {
            String txt;
            try {
                if (projectFile != null) {
                    txt = projectFile.read().orElse("");
                } else {
                    txt = fragment.text().join();
                }
            } catch (Exception e) {
                txt = "Error loading preview: " + e.getMessage();
            }
            final String fTxt = txt;
            final String initialStyle = style;

            SwingUtilities.invokeLater(() -> {
                var panel = new PreviewTextPanel(
                        chrome.getContextManager(), projectFile, fTxt, initialStyle, chrome.getTheme(), fragment);
                showPreviewFrame(initialTitle, panel);
                updateDescriptionAsync(initialTitle, panel, null, fragment);
            });

            // Also resolve syntax style asynchronously and re-render if it differs
            fragment.syntaxStyle().onComplete((resolvedStyle, ex) -> {
                if (ex != null) {
                    logger.debug("Failed to resolve syntax style for fragment {}", fragment.id(), ex);
                    return;
                }
                if (!Objects.equals(resolvedStyle, initialStyle)) {
                    SwingUtilities.invokeLater(() -> renderAndShowPreview(fTxt, resolvedStyle, initialTitle));
                }
            });
            return null;
        });
    }

    /**
     * Preview for computed fragments with immediate or placeholder-based display.
     */
    private void previewVirtualFragment(ContextFragment cf, String initialTitle, @Nullable String computedDescNow) {
        String styleNow = cf.syntaxStyle().renderNowOrNull();
        final String syntaxNow = (styleNow != null) ? styleNow : SyntaxConstants.SYNTAX_STYLE_NONE;

        String textNow = cf.text().renderNowOrNull();

        if (textNow != null) {
            // Immediate display possible
            if (SyntaxConstants.SYNTAX_STYLE_MARKDOWN.equals(syntaxNow)) {
                JPanel contentPanel = renderMarkdownContent(textNow);
                showPreviewFrame(initialTitle, contentPanel);
                if (styleNow == null) {
                    cf.syntaxStyle().onComplete((resolvedStyle, e) -> {
                        if (e == null
                                && !Objects.equals(resolvedStyle, syntaxNow)
                                && !SyntaxConstants.SYNTAX_STYLE_MARKDOWN.equals(resolvedStyle)) {
                            SwingUtilities.invokeLater(
                                    () -> renderAndShowPreview(textNow, resolvedStyle, initialTitle));
                        }
                    });
                }
            } else {
                var previewPanel =
                        new PreviewTextPanel(chrome.getContextManager(), null, textNow, syntaxNow, chrome.getTheme(), cf);
                showPreviewFrame(initialTitle, previewPanel);
                if (styleNow == null) {
                    cf.syntaxStyle().onComplete((resolvedStyle, e) -> {
                        if (e == null && !Objects.equals(resolvedStyle, syntaxNow)) {
                            SwingUtilities.invokeLater(
                                    () -> renderAndShowPreview(textNow, resolvedStyle, initialTitle));
                        }
                    });
                }
            }
            updateDescriptionAsync(initialTitle, null, computedDescNow, cf);
        } else {
            // Placeholder needed; load in background
            var placeholder =
                    new PreviewTextPanel(chrome.getContextManager(), null, "Loading...", syntaxNow, chrome.getTheme(), cf);
            showPreviewFrame(initialTitle, placeholder);

            chrome.getContextManager().submitBackgroundTask("Load computed fragment preview", () -> {
                String txt;
                String style = cf.syntaxStyle().join();
                try {
                    txt = cf.text().join();
                } catch (Exception e) {
                    txt = "Error loading preview: " + e.getMessage();
                    logger.debug("Error computing fragment text", e);
                }
                final String fTxt = txt;
                final String fStyle = style;
                SwingUtilities.invokeLater(() -> renderPreviewContent(fTxt, fStyle, initialTitle));
                return null;
            });

            updateDescriptionAsync(initialTitle, placeholder, computedDescNow, cf);
        }
    }

    /**
     * Updates the fragment description asynchronously if the computed description is not yet available.
     */
    private void updateDescriptionAsync(
            String initialTitle,
            @Nullable PreviewTextPanel placeholder,
            @Nullable String computedDescNow,
            ContextFragment fragment) {
        if ((computedDescNow == null || computedDescNow.isBlank())) {
            fragment.description().onComplete((description, e) -> {
                if (e != null) {
                    logger.warn("Failed to render computed description for fragment {}", fragment.id(), e);
                } else if (placeholder != null) {
                    updateTitleIfNeeded(initialTitle, placeholder, description);
                }
            });
        }
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
     * Renders text content and shows it in a preview frame. Handles both markdown and plain text.
     */
    private void renderPreviewContent(String text, String style, String title) {
        if (SyntaxConstants.SYNTAX_STYLE_MARKDOWN.equals(style)) {
            JPanel contentPanel = renderMarkdownContent(text);
            showPreviewFrame(title, contentPanel);
        } else {
            var panel =
                    new PreviewTextPanel(contextManager, null, text, style, chrome.getTheme(), null);
            showPreviewFrame(title, panel);
        }
    }

    /**
     * Renders text with resolved style and shows it. Used for async re-renders when style changes.
     */
    private void renderAndShowPreview(String text, String resolvedStyle, String title) {
        if (SyntaxConstants.SYNTAX_STYLE_MARKDOWN.equals(resolvedStyle)) {
            JPanel contentPanel = renderMarkdownContent(text);
            showPreviewFrame(title, contentPanel);
        } else {
            var panel =
                    new PreviewTextPanel(contextManager, null, text, resolvedStyle, chrome.getTheme(), null);
            showPreviewFrame(title, panel);
        }
    }

    /**
     * Generates a window key for reuse based on content type and title.
     */
    private String generatePreviewWindowKey(String title, JComponent contentComponent) {
        // When showing a loading placeholder, always use a stable preview-based key so that
        // subsequent async content replacement targets the same window regardless of file association.
        if (title.endsWith("Loading...")) {
            return "preview:" + title;
        }

        if (contentComponent instanceof PreviewTextPanel textPanel && textPanel.getFile() != null) {
            return "file:" + textPanel.getFile().toString();
        }
        if (contentComponent instanceof PreviewImagePanel imagePanel) {
            var bf = imagePanel.getFile();
            if (bf instanceof ProjectFile pf) {
                return "file:" + pf.toString();
            }
        }
        // Fallback: title-based key for non-file content
        return "preview:" + title;
    }

    /**
     * Computes the alternate preview window key for cases where a placeholder (preview-based key)
     * is followed by a final content panel (file-based key), or vice versa. This allows reusing
     * the same window even if the content component switches between file/non-file variants.
     */
    private @Nullable String computeAlternatePreviewKey(String title, JComponent contentComponent, String primaryKey) {
        try {
            String strippedTitle = title.startsWith("Preview: ") ? title.substring(9) : title;

            if (primaryKey.startsWith("file:")) {
                // Attempt preview-based variant
                return "preview:" + title;
            }

            if (primaryKey.startsWith("preview:")) {
                // Attempt file-based variant only if we actually have a file-associated component
                if (contentComponent instanceof PreviewTextPanel ptp) {
                    var file = ptp.getFile();
                    if (file != null) {
                        return "file:" + strippedTitle;
                    }
                } else if (contentComponent instanceof PreviewImagePanel img) {
                    var f = img.getFile();
                    if (f instanceof ProjectFile) {
                        return "file:" + strippedTitle;
                    }
                }
            }
        } catch (Exception ex) {
            logger.debug("computeAlternatePreviewKey failed", ex);
        }
        return null;
    }

    /**
     * Update the window title for an existing preview in a safe EDT manner and repaint.
     */
    private void updatePreviewWindowTitle(String initialTitle, JComponent contentComponent, String newTitle) {
        SwingUtilities.invokeLater(() -> {
            try {
                // Check if it's a PreviewTextPanel in the tabbed frame
                if (contentComponent instanceof PreviewTextPanel textPanel
                        && previewTextFrame != null
                        && previewTextFrame.isDisplayable()) {
                    previewTextFrame.updateTabTitle(textPanel, newTitle);
                    return;
                }

                // Handle regular preview windows
                String key = generatePreviewWindowKey(initialTitle, contentComponent);
                JFrame previewFrame = activePreviewWindows.get(key);
                if (previewFrame == null) {
                    String altKey = computeAlternatePreviewKey(initialTitle, contentComponent, key);
                    if (altKey != null) {
                        previewFrame = activePreviewWindows.get(altKey);
                    }
                }
                if (previewFrame != null) {
                    previewFrame.setTitle(newTitle);
                    if (SystemInfo.isMacOS && SystemInfo.isMacFullWindowContentSupported) {
                        var contentPane = previewFrame.getContentPane();
                        if (contentPane.getLayout() instanceof BorderLayout bl) {
                            Component northComponent = bl.getLayoutComponent(BorderLayout.NORTH);
                            if (northComponent instanceof JPanel titleBar
                                    && titleBar.getLayout() instanceof BorderLayout tbl) {
                                Component centerInTitleBar = tbl.getLayoutComponent(BorderLayout.CENTER);
                                if (centerInTitleBar instanceof JLabel label) {
                                    label.setText(newTitle);
                                }
                            }
                        }
                    }
                    previewFrame.revalidate();
                    previewFrame.repaint();
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
