package ai.brokk.difftool.ui;

import ai.brokk.difftool.node.JMDiffNode;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.util.SlidingWindowCache;
import java.util.List;
import java.util.Set;
import javax.swing.JComponent;
import org.jetbrains.annotations.Nullable;

/**
 * Common interface for diff panel implementations (side-by-side and unified). This interface abstracts the common
 * operations needed by BrokkDiffPanel to work with different diff view types.
 */
public interface IDiffPanel extends ThemeAware, SlidingWindowCache.Disposable {
    // Panel lifecycle
    void resetAutoScrollFlag();

    void resetToFirstDifference();

    void refreshComponentListeners();

    // Diff operations
    @Nullable
    JMDiffNode getDiffNode();

    void setDiffNode(@Nullable JMDiffNode diffNode);

    void diff(boolean autoScroll);

    // Navigation
    void doUp();

    void doDown();

    boolean isAtFirstLogicalChange();

    boolean isAtLastLogicalChange();

    void goToLastLogicalChange();

    // Editing and state
    @Override
    boolean hasUnsavedChanges();

    boolean isUndoEnabled();

    boolean isRedoEnabled();

    void doUndo();

    void doRedo();

    void recalcDirty();

    // Save operations
    List<BufferDiffPanel.AggregatedChange> collectChangesForAggregation();

    BufferDiffPanel.SaveResult writeChangedDocuments();

    void finalizeAfterSaveAggregation(Set<String> successfulFiles);

    // UI
    String getTitle();

    JComponent getComponent(); // Returns the actual Swing component

    // Cleanup
    void clearCaches();

    // Panel type identification
    default boolean isUnifiedView() {
        return false;
    }

    default boolean atLeastOneSideEditable() {
        return true;
    }

    // Per-panel gutter blame toggle (default no-op for implementations that don't support it)
    default void setShowGutterBlame(boolean show) {
        // Default: no-op
    }

    default boolean isShowGutterBlame() {
        return false;
    }

    // Creation context tracking (for debugging)
    default void markCreationContext(String context) {}

    default String getCreationContext() {
        return "unknown";
    }
}
