package ai.brokk.difftool.ui;

import ai.brokk.gui.components.EditorFontSizeControl;

/**
 * Composite interface for diff toolbar actions.
 */
public interface DiffToolbarCallbacks
        extends DiffNavigationCallbacks,
                DiffEditCallbacks,
                DiffViewCallbacks,
                DiffCaptureCallbacks,
                EditorFontSizeControl {}

/** Callbacks for navigation within and between file diffs. */
interface DiffNavigationCallbacks {
    void navigateToNextChange();

    void navigateToPreviousChange();

    void nextFile();

    void previousFile();

    boolean canNavigateToNextChange();

    boolean canNavigateToPreviousChange();

    boolean canNavigateToNextFile();

    boolean canNavigateToPreviousFile();

    boolean isMultiFile();
}

/** Callbacks for editing operations. */
interface DiffEditCallbacks {
    default void performUndo() {}

    default void performRedo() {}

    default void saveAll() {}

    default boolean isUndoEnabled() {
        return false;
    }

    default boolean isRedoEnabled() {
        return false;
    }

    default boolean hasUnsavedChanges() {
        return false;
    }

    default int getUnsavedCount() {
        return 0;
    }
}

/** Callbacks for view mode and display options. */
interface DiffViewCallbacks {
    void switchViewMode(boolean useUnifiedView);

    void setShowBlame(boolean show);

    void setShowAllLines(boolean show);

    void setShowBlankLineDiffs(boolean show);

    boolean isUnifiedView();

    boolean isBlameAvailable();

    boolean isShowingBlame();

    boolean isShowingAllLines();

    boolean isShowingBlankLineDiffs();
}

/** Callbacks for capture operations. */
interface DiffCaptureCallbacks {
    default void captureCurrentDiff() {}

    default void captureAllDiffs() {}
}
