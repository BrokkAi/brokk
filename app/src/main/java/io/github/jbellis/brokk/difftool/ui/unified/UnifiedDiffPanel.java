package io.github.jbellis.brokk.difftool.ui.unified;

import io.github.jbellis.brokk.difftool.node.JMDiffNode;
import io.github.jbellis.brokk.difftool.ui.AbstractDiffPanel;
import io.github.jbellis.brokk.difftool.ui.BrokkDiffPanel;
import io.github.jbellis.brokk.difftool.ui.BufferDiffPanel;
import io.github.jbellis.brokk.difftool.ui.BufferSource;
import io.github.jbellis.brokk.difftool.ui.CompositeHighlighter;
import io.github.jbellis.brokk.difftool.ui.JMHighlighter;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import java.awt.BorderLayout;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jetbrains.annotations.Nullable;

/**
 * Unified diff panel that displays diffs in GitHub-style unified format. This implementation starts as read-only and
 * provides navigation between hunks. Supports diff highlighting similar to side-by-side panels.
 */
public class UnifiedDiffPanel extends AbstractDiffPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(UnifiedDiffPanel.class);

    // Legacy fields for backward compatibility (deprecated)
    @Deprecated
    @Nullable
    private final BufferSource leftSource;

    @Deprecated
    @Nullable
    private final BufferSource rightSource;

    private final RSyntaxTextArea textArea;
    private final RTextScrollPane scrollPane;
    private final JMHighlighter jmHighlighter;
    private final CompositeHighlighter compositeHighlighter;

    @Nullable
    private UnifiedDiffNavigator navigator;

    @Nullable
    private UnifiedDiffDocument unifiedDocument;

    @Nullable
    private UnifiedDiffLineNumberList customLineNumberList;

    private UnifiedDiffDocument.ContextMode contextMode = UnifiedDiffDocument.ContextMode.STANDARD_3_LINES;

    private boolean autoScrollFlag = true;

    // Custom document filter for line-level editing control (initialized in setupUI)
    @SuppressWarnings("unused") // Will be used for future editing support
    private UnifiedDiffDocumentFilter documentFilter;

    /**
     * Preferred constructor using JMDiffNode (integrates with existing diff processing pipeline).
     *
     * @param parent The parent BrokkDiffPanel
     * @param theme The GUI theme
     * @param diffNode The JMDiffNode containing pre-processed diff information
     */
    public UnifiedDiffPanel(BrokkDiffPanel parent, GuiTheme theme, JMDiffNode diffNode) {
        super(parent, theme);
        this.leftSource = null;
        this.rightSource = null;

        // Create text area for unified diff display
        this.textArea = new RSyntaxTextArea();
        this.scrollPane = new RTextScrollPane(textArea);

        // Initialize highlighting system similar to FilePanel
        this.jmHighlighter = new JMHighlighter();
        this.compositeHighlighter = new CompositeHighlighter(jmHighlighter);
        textArea.setHighlighter(compositeHighlighter);

        setupUI();
        setDiffNode(diffNode);
    }

    /**
     * Legacy constructor using BufferSource (deprecated - use JMDiffNode constructor instead). This constructor is
     * retained for backward compatibility.
     *
     * @param parent The parent BrokkDiffPanel
     * @param theme The GUI theme
     * @param leftSource Source for left side
     * @param rightSource Source for right side
     */
    @Deprecated
    public UnifiedDiffPanel(BrokkDiffPanel parent, GuiTheme theme, BufferSource leftSource, BufferSource rightSource) {
        super(parent, theme);
        this.leftSource = leftSource;
        this.rightSource = rightSource;

        // Create text area for unified diff display
        this.textArea = new RSyntaxTextArea();
        this.scrollPane = new RTextScrollPane(textArea);

        // Initialize highlighting system similar to FilePanel
        this.jmHighlighter = new JMHighlighter();
        this.compositeHighlighter = new CompositeHighlighter(jmHighlighter);
        textArea.setHighlighter(compositeHighlighter);

        setupUI();
        generateDiffFromBufferSources();
    }

    /** Set up the UI components and layout. */
    private void setupUI() {
        setLayout(new BorderLayout());
        add(scrollPane, BorderLayout.CENTER);

        // Configure text area
        textArea.setEditable(false); // Start as read-only
        textArea.setCodeFoldingEnabled(false);
        textArea.setAntiAliasingEnabled(true);
        textArea.setAutoIndentEnabled(false);
        textArea.setTabsEmulated(true);
        textArea.setTabSize(4);

        // Custom document filter not needed for plain text approach
        this.documentFilter = new UnifiedDiffDocumentFilter();

        // Add scroll pane configuration with custom line numbering
        scrollPane.setLineNumbersEnabled(false); // Disable built-in line numbering
        scrollPane.setFoldIndicatorEnabled(false);

        // Create custom line number list for unified diff
        customLineNumberList = new UnifiedDiffLineNumberList(textArea);

        // Set the line number component as the row header for scroll synchronization
        scrollPane.setRowHeaderView(customLineNumberList);

        // Apply initial theme (same approach as FilePanel:177)
        GuiTheme.loadRSyntaxTheme(getTheme().isDarkTheme()).ifPresent(theme -> theme.apply(textArea));

        logger.debug("UnifiedDiffPanel UI setup complete");
    }

    /** Generate the unified diff content from JMDiffNode (preferred approach). */
    private void generateDiffFromDiffNode(JMDiffNode diffNode) {
        // Generate the UnifiedDiffDocument (for line number metadata and display content)
        this.unifiedDocument = UnifiedDiffGenerator.generateFromDiffNode(diffNode, contextMode);

        // Extract plain text content from the UnifiedDiffDocument to include OMITTED_LINES
        var textBuilder = new StringBuilder();
        for (var diffLine : unifiedDocument.getFilteredLines()) {
            String content = diffLine.getContent();
            textBuilder.append(content);
            // Only add newline if content doesn't already end with one
            if (!content.endsWith("\n")) {
                textBuilder.append('\n');
            }
        }

        // Remove trailing newline if present
        if (textBuilder.length() > 0 && textBuilder.charAt(textBuilder.length() - 1) == '\n') {
            textBuilder.setLength(textBuilder.length() - 1);
        }

        String plainTextContent = textBuilder.toString();
        textArea.setText(plainTextContent);

        // Link the UnifiedDiffDocument to the custom line number list
        if (customLineNumberList != null) {
            customLineNumberList.setUnifiedDocument(unifiedDocument);
            customLineNumberList.setDarkTheme(getTheme().isDarkTheme());
            customLineNumberList.setContextMode(contextMode);
        }

        this.navigator = new UnifiedDiffNavigator(plainTextContent, textArea);

        // Apply syntax highlighting only (diff coloring disabled)
        applySyntaxHighlighting();

        // Apply diff highlights after content is set
        reDisplay();

        logger.debug("Generated unified diff from JMDiffNode {} with custom line numbering", diffNode.getName());
    }

    /** Generate the unified diff content from BufferSources (legacy approach). */
    @Deprecated
    private void generateDiffFromBufferSources() {
        if (leftSource == null || rightSource == null) {
            logger.error("Cannot generate diff from BufferSources - one or both sources are null");
            throw new IllegalStateException("Cannot generate diff - BufferSources are null");
        }

        // Generate the UnifiedDiffDocument for line number metadata
        this.unifiedDocument = UnifiedDiffGenerator.generateUnifiedDiff(leftSource, rightSource, contextMode);

        // Extract plain text manually for legacy approach - fix interlaced line issue
        var textBuilder = new StringBuilder();
        for (var diffLine : unifiedDocument.getFilteredLines()) {
            String content = diffLine.getContent();
            // Don't double-add newlines - the content should be clean line content
            textBuilder.append(content);
            textBuilder.append('\n');
        }

        // Remove trailing newline if present
        if (textBuilder.length() > 0 && textBuilder.charAt(textBuilder.length() - 1) == '\n') {
            textBuilder.setLength(textBuilder.length() - 1);
        }

        String plainTextContent = textBuilder.toString();
        textArea.setText(plainTextContent);

        // Link the UnifiedDiffDocument to the custom line number list
        if (customLineNumberList != null) {
            customLineNumberList.setUnifiedDocument(unifiedDocument);
            customLineNumberList.setDarkTheme(getTheme().isDarkTheme());
            customLineNumberList.setContextMode(contextMode);
        }

        this.navigator = new UnifiedDiffNavigator(plainTextContent, textArea);

        // Apply syntax highlighting only (diff coloring disabled)
        applySyntaxHighlighting();

        // Apply diff highlights after content is set
        reDisplay();

        logger.debug("Generated unified diff from BufferSources with custom line numbering");
    }

    @Override
    public void setDiffNode(@Nullable JMDiffNode diffNode) {
        super.setDiffNode(diffNode);
        if (diffNode != null) {
            generateDiffFromDiffNode(diffNode);
        } else {
            logger.warn("setDiffNode called with null - clearing unified diff content");
            // Clear the content when no diff node is provided
            textArea.setText("");
            this.unifiedDocument = null;
            this.navigator = new UnifiedDiffNavigator("", textArea);

            // Clear the line number list reference
            if (customLineNumberList != null) {
                customLineNumberList.clearUnifiedDocument();
            }

            // Clear highlights when no content
            removeHighlights();
        }
    }

    /** Apply syntax highlighting based on detected file type using FilePanel's approach. */
    private void applySyntaxHighlighting() {
        updateSyntaxStyle(); // Use shared logic from AbstractDiffPanel - pure syntax highlighting only
        logger.debug("Applied pure syntax highlighting using shared AbstractDiffPanel logic");
    }

    /**
     * Chooses a syntax style for the current document based on its filename. Uses shared logic from
     * AbstractDiffPanel.detectSyntaxStyle().
     */
    private void updateSyntaxStyle() {
        var diffNode = getDiffNode();
        var filename = diffNode != null ? diffNode.getName() : null;

        // Note: In unified diff view, we can't inherit from side-by-side panels
        // since they may not exist, so we pass null for fallbackEditor
        var style = AbstractDiffPanel.detectSyntaxStyle(filename, null);

        textArea.setSyntaxEditingStyle(style);
        logger.debug("Set syntax style to: {} for unified diff", style);
    }

    /** Set the context mode for the unified diff. */
    public void setContextMode(UnifiedDiffDocument.ContextMode contextMode) {
        // Always execute context mode changes - the previous condition was incorrectly preventing execution
        // This happens because document generation can modify this.contextMode internally
        logger.debug("Switching context mode from {} to {}", this.contextMode, contextMode);

        this.contextMode = contextMode;

        // Always regenerate document to ensure context switching works reliably in both directions
        // Previous asymmetric approach (regenerate for FULL_CONTEXT, filter for STANDARD_3_LINES)
        // caused issues when switching from FULL_CONTEXT back to STANDARD_3_LINES
        {
            logger.debug("Regenerating document for context mode: {}", contextMode);

                // Regenerate the document with the target context mode
                var diffNode = getDiffNode();
                if (diffNode != null) {
                    generateDiffFromDiffNode(diffNode);
                } else if (leftSource != null && rightSource != null) {
                    generateDiffFromBufferSources();
                } else {
                    logger.warn("No source available for regenerating diff");
                }

                // Update text area display after regeneration
                updateTextAreaFromDocument();

                // Force repaint to ensure immediate visual update
                textArea.repaint();
                textArea.revalidate();

                // Update navigator with new content
                if (navigator != null) {
                    navigator.refreshHunkPositions();
                }
        }

        // Update the line number list with new context
        if (customLineNumberList != null && unifiedDocument != null) {
            customLineNumberList.setUnifiedDocument(unifiedDocument);
            customLineNumberList.setContextMode(contextMode);
            // Force repaint of the line number component
            customLineNumberList.revalidate();
            customLineNumberList.repaint();
        }

        textArea.revalidate();
        textArea.repaint();
    }

    /** Update text area content from the current UnifiedDiffDocument. */
    private void updateTextAreaFromDocument() {
        if (unifiedDocument == null) {
            textArea.setText("");
            return;
        }

        // Extract plain text content from the UnifiedDiffDocument to include OMITTED_LINES
        var textBuilder = new StringBuilder();
        for (var diffLine : unifiedDocument.getFilteredLines()) {
            String content = diffLine.getContent();
            textBuilder.append(content);
            // Only add newline if content doesn't already end with one
            if (!content.endsWith("\n")) {
                textBuilder.append('\n');
            }
        }

        // Remove trailing newline if present
        if (textBuilder.length() > 0 && textBuilder.charAt(textBuilder.length() - 1) == '\n') {
            textBuilder.setLength(textBuilder.length() - 1);
        }

        String plainTextContent = textBuilder.toString();
        textArea.setText(plainTextContent);

        logger.debug(
                "Updated text area content from document - {} characters, {} lines",
                plainTextContent.length(),
                textArea.getLineCount());
    }

    /** Get the current context mode. */
    public UnifiedDiffDocument.ContextMode getContextMode() {
        return contextMode;
    }

    // IDiffPanel implementation

    @Override
    public boolean isUnifiedView() {
        return true;
    }

    @Override
    public void resetAutoScrollFlag() {
        this.autoScrollFlag = true;
    }

    @Override
    public void resetToFirstDifference() {
        if (navigator != null) {
            navigator.goToFirstHunk();
        }
    }

    @Override
    public void diff(boolean autoScroll) {
        // Note: No need to regenerate diff since it's already processed in JMDiffNode
        // This method is primarily for navigation and scrolling

        if (autoScroll && autoScrollFlag) {
            resetToFirstDifference();
            autoScrollFlag = false;
        }

        // Theme-based coloring is handled automatically by RSyntaxTextArea
    }

    @Override
    public void doUp() {
        if (navigator != null) {
            navigator.navigateToPreviousHunk();
        }
    }

    @Override
    public void doDown() {
        if (navigator != null) {
            navigator.navigateToNextHunk();
        }
    }

    @Override
    public boolean isAtFirstLogicalChange() {
        return navigator == null || navigator.isAtFirstHunk();
    }

    @Override
    public boolean isAtLastLogicalChange() {
        return navigator == null || navigator.isAtLastHunk();
    }

    @Override
    public void goToLastLogicalChange() {
        if (navigator != null) {
            navigator.goToLastHunk();
        }
    }

    @Override
    public String getTitle() {
        // Try to get title from JMDiffNode first (preferred)
        var diffNode = getDiffNode();
        if (diffNode != null) {
            var nodeName = diffNode.getName();
            if (nodeName != null && !nodeName.isEmpty()) {
                // Extract filename from path for display
                var lastSlash = nodeName.lastIndexOf('/');
                var displayName = lastSlash >= 0 && lastSlash < nodeName.length() - 1
                        ? nodeName.substring(lastSlash + 1)
                        : nodeName;
                return displayName + " (Unified)";
            }
        }

        // Fallback to legacy BufferSource approach
        if (leftSource != null && rightSource != null) {
            var leftName = leftSource.title();
            var rightName = rightSource.title();
            var title = leftName.equals(rightName) ? leftName : leftName + " vs " + rightName;
            // Add unified indicator
            return title + " (Unified)";
        }

        // Ultimate fallback
        return "Unified Diff";
    }

    @Override
    public boolean hasUnsavedChanges() {
        // TODO: Implement change tracking for editing support
        return false; // Read-only for now
    }

    @Override
    public boolean isUndoEnabled() {
        // TODO: Implement undo support
        return false;
    }

    @Override
    public boolean isRedoEnabled() {
        // TODO: Implement redo support
        return false;
    }

    @Override
    public void doUndo() {
        // TODO: Implement
        logger.debug("Undo requested (not implemented)");
    }

    @Override
    public void doRedo() {
        // TODO: Implement
        logger.debug("Redo requested (not implemented)");
    }

    @Override
    public void recalcDirty() {
        // TODO: Implement change tracking
        logger.trace("Recalc dirty requested (not implemented)");
    }

    @Override
    public List<BufferDiffPanel.AggregatedChange> collectChangesForAggregation() {
        // TODO: Implement for editing support
        return List.of();
    }

    @Override
    public BufferDiffPanel.SaveResult writeChangedDocuments() {
        // TODO: Implement for editing support
        return new BufferDiffPanel.SaveResult(Set.of(), Map.of());
    }

    @Override
    public void finalizeAfterSaveAggregation(Set<String> successfulFiles) {
        // TODO: Implement
        logger.debug("Finalize after save aggregation requested (not implemented)");
    }

    @Override
    public void refreshComponentListeners() {
        // Remove existing listeners to avoid duplicates
        var listeners = getComponentListeners();
        for (var listener : listeners) {
            removeComponentListener(listener);
        }

        // Add resize listener for proper text area and scroll pane layout updates
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                SwingUtilities.invokeLater(() -> {
                    // Force revalidation of scroll pane and text area
                    if (scrollPane != null) {
                        scrollPane.revalidate();
                        scrollPane.repaint();
                    }
                    if (textArea != null) {
                        textArea.revalidate();
                        textArea.repaint();
                    }
                    // Force revalidation of custom line number list
                    if (customLineNumberList != null) {
                        customLineNumberList.revalidate();
                        customLineNumberList.repaint();
                    }
                });
            }
        });

        // Update navigator if caret position changed
        if (navigator != null) {
            navigator.updateCurrentHunkFromCaret();
        }
    }

    @Override
    public void clearCaches() {
        // Clear any internal caches
        logger.debug("Clear caches requested");
    }

    @Override
    public void dispose() {
        // Clean up resources
        super.dispose();
        logger.debug("UnifiedDiffPanel disposed");
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        // Apply theme to syntax highlighting
        GuiTheme.loadRSyntaxTheme(guiTheme.isDarkTheme()).ifPresent(theme -> {
            // Update syntax style first
            updateSyntaxStyle();

            // Apply theme to the composite highlighter (which will forward to JMHighlighter)
            if (compositeHighlighter != null) {
                compositeHighlighter.applyTheme(guiTheme);
            }

            // Apply RSyntax theme
            theme.apply(textArea);

            // Update line number list theme
            if (customLineNumberList != null) {
                customLineNumberList.setDarkTheme(guiTheme.isDarkTheme());
            }

            // Refresh highlights with new theme colors
            reDisplay();

            logger.debug("Applied theme and refreshed highlights in UnifiedDiffPanel");
        });
    }

    /**
     * Custom document filter for controlling which lines can be edited. This will be used when editing support is
     * added.
     */
    private class UnifiedDiffDocumentFilter extends DocumentFilter {
        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
                throws BadLocationException {
            if (isEditAllowed(offset)) {
                super.insertString(fb, offset, string, attr);
            } else {
                logger.debug("Insert blocked at offset {} (non-editable line)", offset);
            }
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                throws BadLocationException {
            if (isEditAllowed(offset)) {
                super.replace(fb, offset, length, text, attrs);
            } else {
                logger.debug("Replace blocked at offset {} (non-editable line)", offset);
            }
        }

        @Override
        public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
            if (isEditAllowed(offset)) {
                super.remove(fb, offset, length);
            } else {
                logger.debug("Remove blocked at offset {} (non-editable line)", offset);
            }
        }

        private boolean isEditAllowed(int offset) {
            try {
                int line = textArea.getLineOfOffset(offset);
                // For plain text approach, determine editability by line content
                String lineContent = textArea.getDocument()
                        .getText(
                                textArea.getLineStartOffset(line),
                                textArea.getLineEndOffset(line) - textArea.getLineStartOffset(line));

                // Allow editing of addition lines (+) and context lines (space), but not deletion lines (-) or headers
                // (@@)
                return !lineContent.startsWith("-") && !lineContent.startsWith("@@");
            } catch (BadLocationException e) {
                logger.warn("Failed to check edit permission for offset {}", offset, e);
                return false; // Deny edit if we can't determine
            }
        }
    }

    /** Get navigation info for debugging. */
    public String getNavigationInfo() {
        return navigator != null ? navigator.getNavigationInfo() : "No navigator";
    }

    /** Get the JMHighlighter for external access (similar to FilePanel). */
    public JMHighlighter getHighlighter() {
        return jmHighlighter;
    }

    /** Remove all diff highlights from the highlighter. */
    private void removeHighlights() {
        UnifiedDiffHighlighter.removeHighlights(jmHighlighter);
        logger.debug("Removed all diff highlights from unified diff panel");
    }

    /** Apply diff highlights to the current unified diff content. */
    private void applyHighlights() {
        try {
            boolean isDarkTheme = getTheme().isDarkTheme();
            UnifiedDiffHighlighter.applyHighlights(textArea, jmHighlighter, isDarkTheme);
            logger.debug("Applied diff highlights to unified diff content");
        } catch (Exception e) {
            logger.warn("Failed to apply highlights: {}", e.getMessage(), e);
        }
    }

    /** Refresh highlights and force repaint (similar to FilePanel.reDisplayInternal). */
    @Override
    public void reDisplay() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::reDisplay);
            return;
        }

        try {
            removeHighlights();
            applyHighlights();

            // Force both the JMHighlighter and editor to repaint
            jmHighlighter.repaint();
            textArea.repaint();

            logger.debug("UnifiedDiffPanel reDisplay completed");
        } catch (Exception e) {
            logger.warn("Error during unified diff reDisplay: {}", e.getMessage(), e);
        }
    }
}
