package ai.brokk.difftool.ui;

import ai.brokk.gui.components.EditorFontSizeControl;

/**
 * Callbacks interface for diff toolbar actions.
 * Implementations provide the actual behavior for toolbar button actions.
 */
public interface DiffToolbarCallbacks extends EditorFontSizeControl {

    // --- Navigation ---

    /** Navigate to the next change within the current file */
    void navigateToNextChange();

    /** Navigate to the previous change within the current file */
    void navigateToPreviousChange();

    /** Navigate to the next file (multi-file diffs) */
    void nextFile();

    /** Navigate to the previous file (multi-file diffs) */
    void previousFile();

    // --- Edit operations (optional - may be unsupported in read-only mode) ---

    /** Perform undo operation */
    void performUndo();

    /** Perform redo operation */
    void performRedo();

    /** Save all changes */
    void saveAll();

    // --- View mode ---

    /** Switch between unified and side-by-side view */
    void switchViewMode(boolean useUnifiedView);

    /** Toggle git blame display */
    void setShowBlame(boolean show);

    /** Toggle showing all lines in unified view */
    void setShowAllLines(boolean show);

    /** Toggle showing blank line diffs in side-by-side view */
    void setShowBlankLineDiffs(boolean show);

    // --- Capture operations (optional - may be unsupported in read-only mode) ---

    /** Capture the current file's diff */
    void captureCurrentDiff();

    /** Capture all files' diffs */
    void captureAllDiffs();

    // --- State queries for button enable/disable ---

    /** @return true if navigation to next change is possible */
    boolean canNavigateToNextChange();

    /** @return true if navigation to previous change is possible */
    boolean canNavigateToPreviousChange();

    /** @return true if navigation to next file is possible */
    boolean canNavigateToNextFile();

    /** @return true if navigation to previous file is possible */
    boolean canNavigateToPreviousFile();

    /** @return true if undo is available */
    boolean isUndoEnabled();

    /** @return true if redo is available */
    boolean isRedoEnabled();

    /** @return true if there are unsaved changes */
    boolean hasUnsavedChanges();

    /** @return count of files with unsaved changes */
    int getUnsavedCount();

    /** @return true if currently in unified view mode */
    boolean isUnifiedView();

    /** @return true if git blame feature is available */
    boolean isBlameAvailable();

    /** @return true if this is a multi-file diff */
    boolean isMultiFile();

    /** @return true if blame is currently being shown */
    boolean isShowingBlame();

    /** @return true if all lines are shown in unified view */
    boolean isShowingAllLines();

    /** @return true if blank line diffs are shown */
    boolean isShowingBlankLineDiffs();
}
