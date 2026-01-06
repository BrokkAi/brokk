package ai.brokk.difftool.ui;

import ai.brokk.analyzer.ProjectFile;

/**
 * Internal interface for components that handle index-based navigation within a multi-file diff view.
 * This is primarily used by UI components like the file tree or next/prev buttons.
 *
 * Use {@link DiffProjectFileNavigationTarget} for external drivers.
 */
public interface DiffNavigationTarget extends DiffProjectFileNavigationTarget {

    /**
     * Navigates to a specific file by its index.
     * Internal convenience for tree selection.
     *
     * @param fileIndex The index of the file in the comparison list.
     */
    void navigateToFile(int fileIndex);

    /**
     * Navigates to a specific file and scrolls to a particular line number.
     * Internal convenience for tree selection.
     *
     * @param fileIndex  The index of the file in the comparison list.
     * @param lineNumber The 1-based line number to navigate to.
     */
    void navigateToLocation(int fileIndex, int lineNumber);

    /**
     * Returns the index of the file currently being displayed.
     *
     * @return The 0-based index of the current file.
     */
    int getCurrentFileIndex();

    /**
     * Returns the total number of file comparisons available.
     *
     * @return The count of file comparisons.
     */
    int getFileComparisonCount();
}
