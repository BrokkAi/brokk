package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.difftool.node.JMDiffNode;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.util.SyntaxDetector;
import java.util.List;
import java.util.Locale;
import javax.swing.JComponent;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

/**
 * Base class for diff panel implementations with common functionality. This class provides default implementations for
 * common operations and maintains shared state between different diff view types.
 *
 * <p>Note: This extends AbstractContentPanel to leverage existing undo/redo infrastructure while adding IDiffPanel
 * functionality.
 */
public abstract class AbstractDiffPanel extends AbstractContentPanel implements IDiffPanel {
    protected final BrokkDiffPanel parent;
    protected final GuiTheme theme;

    @Nullable
    protected JMDiffNode diffNode;

    protected String creationContext = "unknown";

    /**
     * Per-panel UI flag: whether to show git blame information under the gutter line numbers. Panels should honor this
     * flag and update their gutter rendering accordingly.
     */
    protected volatile boolean showGutterBlame = false;

    public AbstractDiffPanel(BrokkDiffPanel parent, GuiTheme theme) {
        this.parent = parent;
        this.theme = theme;
    }

    // Common implementations from IDiffPanel

    @Override
    public JComponent getComponent() {
        return this; // The panel itself is the component
    }

    @Override
    @Nullable
    public JMDiffNode getDiffNode() {
        return diffNode;
    }

    @Override
    public void setDiffNode(@Nullable JMDiffNode diffNode) {
        this.diffNode = diffNode;
    }

    @Override
    public void markCreationContext(String context) {
        this.creationContext = context;
    }

    @Override
    public String getCreationContext() {
        return creationContext;
    }

    /** Per-panel gutter blame controls. */
    @Override
    public void setShowGutterBlame(boolean show) {
        this.showGutterBlame = show;
    }

    @Override
    public boolean isShowGutterBlame() {
        return this.showGutterBlame;
    }

    // Provide access to parent for subclasses
    protected BrokkDiffPanel getDiffParent() {
        return parent;
    }

    protected GuiTheme getTheme() {
        return theme;
    }

    // Default implementations that subclasses may override
    @Override
    public void refreshComponentListeners() {
        // Default implementation - subclasses can override if needed
    }

    @Override
    public void clearCaches() {
        // Default implementation - subclasses can override if needed
    }

    @Override
    public void dispose() {
        // Default cleanup - subclasses should override and call super
        removeAll();
        this.diffNode = null;
    }

    // IDiffPanel implementations that delegate to existing AbstractContentPanel methods
    // These provide the bridge between IDiffPanel interface and existing functionality

    // Navigation methods are already defined in AbstractContentPanel as abstract
    // isAtFirstLogicalChange(), isAtLastLogicalChange(), goToLastLogicalChange()
    // doUp(), doDown() are already implemented in AbstractContentPanel

    // Undo/redo methods are already implemented in AbstractContentPanel
    // isUndoEnabled(), doUndo(), isRedoEnabled(), doRedo()

    /**
     * Shared syntax detection logic for all diff panels. Chooses a syntax style for the current document based on its
     * filename with robust cleanup.
     *
     * @param filename The filename to analyze (can be null)
     * @param fallbackEditor Optional editor to inherit syntax style from if filename detection fails
     * @return The detected syntax style string
     */
    protected static String detectSyntaxStyle(@Nullable String filename, @Nullable RSyntaxTextArea fallbackEditor) {
        /*
         * Heuristic 1: strip well-known VCS/backup suffixes and decide
         *              the style from the remaining extension.
         * Heuristic 2: if still undecided, inherit the style from the fallback editor
         */
        var style = SyntaxConstants.SYNTAX_STYLE_NONE;

        // --------------------------- Heuristic 1 -----------------------------
        if (filename != null && !filename.isBlank()) {
            // Remove trailing '~'
            var candidate = filename.endsWith("~") ? filename.substring(0, filename.length() - 1) : filename;

            // Remove dotted suffixes (case-insensitive)
            for (var suffix : List.of("orig", "base", "mine", "theirs", "backup")) {
                var sfx = "." + suffix;
                if (candidate.toLowerCase(Locale.ROOT).endsWith(sfx)) {
                    candidate = candidate.substring(0, candidate.length() - sfx.length());
                    break;
                }
            }

            // Remove git annotations like " (HEAD)" from the end
            var parenIndex = candidate.lastIndexOf(" (");
            if (parenIndex > 0) {
                candidate = candidate.substring(0, parenIndex);
            }

            // Extract just the filename from a path if needed
            var lastSlash = candidate.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < candidate.length() - 1) {
                candidate = candidate.substring(lastSlash + 1);
            }

            // Extract extension
            var lastDot = candidate.lastIndexOf('.');
            if (lastDot > 0 && lastDot < candidate.length() - 1) {
                var ext = candidate.substring(lastDot + 1).toLowerCase(Locale.ROOT);
                style = SyntaxDetector.fromExtension(ext);
            }
        }

        // --------------------------- Heuristic 2 -----------------------------
        if (SyntaxConstants.SYNTAX_STYLE_NONE.equals(style) && fallbackEditor != null) {
            var fallbackStyle = fallbackEditor.getSyntaxEditingStyle();
            if (!SyntaxConstants.SYNTAX_STYLE_NONE.equals(fallbackStyle)) {
                style = fallbackStyle;
            }
        }

        return style;
    }

    /**
     * Abstract method for refreshing highlights and repainting the diff panel. Each implementation should handle its
     * own highlight refresh logic.
     */
    public abstract void reDisplay();
}
