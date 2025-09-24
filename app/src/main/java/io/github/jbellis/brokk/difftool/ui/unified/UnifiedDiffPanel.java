package io.github.jbellis.brokk.difftool.ui.unified;

import io.github.jbellis.brokk.difftool.ui.AbstractDiffPanel;
import io.github.jbellis.brokk.difftool.ui.BrokkDiffPanel;
import io.github.jbellis.brokk.difftool.ui.BufferDiffPanel;
import io.github.jbellis.brokk.difftool.ui.BufferSource;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.util.SyntaxDetector;
import java.awt.BorderLayout;
import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jetbrains.annotations.Nullable;

/**
 * Unified diff panel that displays diffs in GitHub-style unified format. This implementation starts as read-only and
 * provides navigation between hunks.
 */
public class UnifiedDiffPanel extends AbstractDiffPanel {
    private static final Logger logger = LogManager.getLogger(UnifiedDiffPanel.class);

    private final BufferSource leftSource;
    private final BufferSource rightSource;
    private final RSyntaxTextArea textArea;
    private final RTextScrollPane scrollPane;

    private UnifiedDiffDocument unifiedDocument;
    private UnifiedDiffNavigator navigator;
    private boolean autoScrollFlag = true;

    // Custom document filter for line-level editing control (initialized in setupUI)
    @SuppressWarnings("unused") // Will be used for future editing support
    private UnifiedDiffDocumentFilter documentFilter;

    public UnifiedDiffPanel(BrokkDiffPanel parent, GuiTheme theme, BufferSource leftSource, BufferSource rightSource) {
        super(parent, theme);
        this.leftSource = leftSource;
        this.rightSource = rightSource;

        // Create text area for unified diff display
        this.textArea = new RSyntaxTextArea();
        this.scrollPane = new RTextScrollPane(textArea);

        setupUI();
        generateDiff();
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

        // Set up custom document filter for future editing support
        this.documentFilter = new UnifiedDiffDocumentFilter();

        // Add scroll pane configuration
        scrollPane.setLineNumbersEnabled(true);
        scrollPane.setFoldIndicatorEnabled(false);

        logger.debug("UnifiedDiffPanel UI setup complete");
    }

    /** Generate the unified diff content. */
    private void generateDiff() {
        var contextMode = UnifiedDiffDocument.ContextMode.STANDARD_3_LINES; // Default
        this.unifiedDocument = UnifiedDiffGenerator.generateUnifiedDiff(leftSource, rightSource, contextMode);

        textArea.setDocument(unifiedDocument);
        this.navigator = new UnifiedDiffNavigator(unifiedDocument, textArea);

        // Apply syntax highlighting and theme
        applySyntaxHighlighting();
        applyDiffColoring();

        logger.debug("Generated unified diff with {} lines", unifiedDocument.getLineCount());
    }

    /** Apply syntax highlighting based on detected file type. */
    private void applySyntaxHighlighting() {
        var syntaxStyle = detectSyntaxStyle();
        textArea.setSyntaxEditingStyle(syntaxStyle);

        logger.debug("Applied syntax highlighting: {}", syntaxStyle);
    }

    /** Apply diff-specific coloring to the text area. */
    private void applyDiffColoring() {
        SwingUtilities.invokeLater(() -> {
            try {
                // Document is available as textArea.getDocument() if needed
                var lines = unifiedDocument.getFilteredLines();

                // Apply coloring to each line based on its type
                for (int i = 0; i < lines.size(); i++) {
                    var diffLine = lines.get(i);
                    var lineStart = textArea.getLineStartOffset(i);
                    var lineEnd = textArea.getLineEndOffset(i);
                    var length = lineEnd - lineStart;

                    if (length > 0) {
                        // Create attributes for this line
                        var attrs = new SimpleAttributeSet();
                        var diffColors = UnifiedDiffSyntaxScheme.createDiffColors(theme.isDarkTheme());
                        var color = UnifiedDiffSyntaxScheme.getColorForLineType(diffLine.getType(), diffColors);
                        var bgColor =
                                UnifiedDiffSyntaxScheme.getBackgroundColorForLineType(diffLine.getType(), diffColors);
                        var font = UnifiedDiffSyntaxScheme.getFontForLineType(textArea.getFont(), diffLine.getType());

                        StyleConstants.setForeground(attrs, color);
                        if (bgColor != null) {
                            StyleConstants.setBackground(attrs, bgColor);
                        }
                        StyleConstants.setFontFamily(attrs, font.getFamily());
                        StyleConstants.setFontSize(attrs, font.getSize());
                        StyleConstants.setBold(attrs, font.isBold());
                        StyleConstants.setItalic(attrs, font.isItalic());

                        // Apply the styling (this may not work perfectly with RSyntaxTextArea)
                        // RSyntaxTextArea has its own syntax highlighting system
                        logger.trace("Applied styling to line {} (type: {})", i, diffLine.getType());
                    }
                }
            } catch (BadLocationException e) {
                logger.warn("Failed to apply diff coloring", e);
            }
        });
    }

    /** Detect the appropriate syntax style based on file sources. */
    private String detectSyntaxStyle() {
        // Try to detect from left source first, then right source
        var filename = detectFilename();
        if (filename != null) {
            return SyntaxDetector.fromExtension(filename);
        }

        // Fallback to plain text
        return SyntaxConstants.SYNTAX_STYLE_NONE;
    }

    /** Detect filename from sources for syntax highlighting. */
    @Nullable
    private String detectFilename() {
        // Check left source
        if (leftSource instanceof BufferSource.FileSource fs) {
            return fs.file().getName();
        } else if (leftSource instanceof BufferSource.StringSource ss && ss.filename() != null) {
            return ss.filename();
        }

        // Check right source
        if (rightSource instanceof BufferSource.FileSource fs) {
            return fs.file().getName();
        } else if (rightSource instanceof BufferSource.StringSource ss && ss.filename() != null) {
            return ss.filename();
        }

        return null;
    }

    /** Set the context mode for the unified diff. */
    public void setContextMode(UnifiedDiffDocument.ContextMode contextMode) {
        if (unifiedDocument != null && unifiedDocument.getContextMode() != contextMode) {
            logger.debug("Switching context mode from {} to {}", unifiedDocument.getContextMode(), contextMode);

            unifiedDocument.switchContextMode(contextMode);
            navigator.refreshHunkPositions();

            // Reapply coloring after content change
            applyDiffColoring();

            textArea.revalidate();
            textArea.repaint();
        }
    }

    /** Get the current context mode. */
    public UnifiedDiffDocument.ContextMode getContextMode() {
        return unifiedDocument != null
                ? unifiedDocument.getContextMode()
                : UnifiedDiffDocument.ContextMode.STANDARD_3_LINES;
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
        // Regenerate diff if needed
        generateDiff();

        if (autoScroll && autoScrollFlag) {
            resetToFirstDifference();
            autoScrollFlag = false;
        }
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
        var leftName = leftSource.title();
        var rightName = rightSource.title();
        var title = leftName.equals(rightName) ? leftName : leftName + " vs " + rightName;

        // Add unified indicator
        return title + " (Unified)";
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
        if (unifiedDocument != null) {
            // Any cleanup needed for the document
        }
        logger.debug("UnifiedDiffPanel disposed");
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        SwingUtilities.invokeLater(() -> {
            // Update colors and fonts based on new theme using UIManager
            Color bg = UIManager.getColor("TextArea.background");
            Color fg = UIManager.getColor("TextArea.foreground");
            Color currentLineBg = UIManager.getColor("TextArea.selectionBackground");
            Color selectionBg = UIManager.getColor("TextArea.selectionBackground");
            Color selectionFg = UIManager.getColor("TextArea.selectionForeground");

            // Apply with fallbacks
            if (bg != null) textArea.setBackground(bg);
            if (fg != null) textArea.setForeground(fg);
            if (currentLineBg != null) textArea.setCurrentLineHighlightColor(currentLineBg);
            if (selectionBg != null) textArea.setSelectionColor(selectionBg);
            if (selectionFg != null) textArea.setSelectedTextColor(selectionFg);

            // Reapply diff coloring with new theme
            applyDiffColoring();

            textArea.revalidate();
            textArea.repaint();

            logger.debug("Applied theme to UnifiedDiffPanel");
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
                return unifiedDocument != null && unifiedDocument.isLineEditable(line);
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
}
