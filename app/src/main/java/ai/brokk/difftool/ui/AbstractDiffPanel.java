package ai.brokk.difftool.ui;

import ai.brokk.difftool.node.JMDiffNode;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.util.SlidingWindowCache;
import ai.brokk.util.SyntaxDetector;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

/**
 * Base class for diff panel implementations with common functionality. This class provides default implementations for
 * common operations and maintains shared state between different diff view types.
 *
 * <p>Note: This extends AbstractContentPanel to leverage existing undo/redo infrastructure while providing diff panel
 * functionality.
 */
public abstract class AbstractDiffPanel extends AbstractContentPanel
        implements ThemeAware, SlidingWindowCache.Disposable {
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

    /** Cached dirty state. Updated by recalcDirty(). */
    protected volatile boolean dirty = false;

    public AbstractDiffPanel(BrokkDiffPanel parent, GuiTheme theme) {
        this.parent = parent;
        this.theme = theme;
    }

    // Panel lifecycle - abstract methods that subclasses must implement
    public abstract void resetAutoScrollFlag();

    public abstract void resetToFirstDifference();

    // Diff operations - abstract methods that subclasses must implement
    public abstract void diff(boolean autoScroll);

    // Navigation - abstract methods that subclasses must implement
    public abstract void doUp();

    public abstract void doDown();

    public abstract boolean isAtFirstLogicalChange();

    public abstract boolean isAtLastLogicalChange();

    public abstract void goToLastLogicalChange();

    // Editing and state - abstract methods that subclasses must implement
    public abstract List<BufferDiffPanel.AggregatedChange> collectChangesForAggregation();

    public abstract BufferDiffPanel.SaveResult writeChangedDocuments();

    public abstract void finalizeAfterSaveAggregation(Set<String> successfulFiles);

    /**
     * Compute whether this panel has unsaved changes. Subclasses implement policy; the base class manages the
     * mechanism via recalcDirty().
     *
     * @return true if there are unsaved changes
     */
    protected abstract boolean computeUnsavedChanges();

    // UI - abstract methods that subclasses must implement
    public abstract String getTitle();

    // Common implementations
    public JComponent getComponent() {
        return this; // The panel itself is the component
    }

    @Nullable
    public JMDiffNode getDiffNode() {
        return diffNode;
    }

    public void setDiffNode(@Nullable JMDiffNode diffNode) {
        this.diffNode = diffNode;
    }

    public void markCreationContext(String context) {
        this.creationContext = context;
    }

    public String getCreationContext() {
        return creationContext;
    }

    /** Per-panel gutter blame controls. */
    public void setShowGutterBlame(boolean show) {
        this.showGutterBlame = show;
    }

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
    public void refreshComponentListeners() {
        // Default implementation - subclasses can override if needed
    }

    public void clearCaches() {
        // Default implementation - subclasses can override if needed
    }

    /**
     * Recalculate dirty state and notify parent if state changed. This is the standard mechanism for updating unsaved
     * change tracking. Call this after document modifications, saves, or undo/redo operations.
     */
    public final void recalcDirty() {
        boolean newDirty = computeUnsavedChanges();
        if (dirty != newDirty) {
            dirty = newDirty;
            onDirtyStateChanged(newDirty);
        }
    }

    /**
     * Hook called when dirty state transitions. Subclasses can override to notify parent or update UI.
     *
     * @param isDirty the new dirty state
     */
    protected void onDirtyStateChanged(boolean isDirty) {
        // Default implementation: notify parent to update tab title and buttons
        SwingUtilities.invokeLater(() -> {
            parent.refreshTabTitle(this);
            parent.updateUndoRedoButtons();
        });
    }

    @Override
    public void dispose() {
        // Default cleanup - subclasses should override and call super
        removeAll();
        this.diffNode = null;
    }

    public boolean atLeastOneSideEditable() {
        return true;
    }

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

    // Blame support - every diff panel must implement these methods

    /**
     * Apply blame information to this diff panel. The maps use 1-based line numbers as keys.
     *
     * @param leftMap blame information for the left/original side (may be empty)
     * @param rightMap blame information for the right/revised side (may be empty)
     */
    public abstract void applyBlame(
            Map<Integer, ai.brokk.difftool.ui.BlameService.BlameInfo> leftMap,
            Map<Integer, ai.brokk.difftool.ui.BlameService.BlameInfo> rightMap);

    /** Clear all blame information from this diff panel. */
    public abstract void clearBlame();

    /**
     * Get the file path for which this panel should display blame information. Used to resolve the target file for
     * blame operations. Returns null if blame is not applicable for this panel.
     *
     * @return the absolute path to the file to blame, or null if unavailable
     */
    @Nullable
    public abstract java.nio.file.Path getTargetPathForBlame();

    // Font support - every diff panel must implement

    /**
     * Apply a specific font size to all editors and gutters in this panel.
     *
     * @param size the font size in points
     */
    public abstract void applyEditorFontSize(float size);

    /**
     * Returns whether this panel has unsaved changes. The base implementation returns the cached dirty flag.
     */
    @Override
    public final boolean hasUnsavedChanges() {
        return dirty;
    }
}
