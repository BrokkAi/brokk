package ai.brokk.difftool.ui;

/**
 * Interface for components that can handle navigation within a multi-file diff view.
 * This abstracts the navigation logic allowing different UI components (like tree or list)
 * to drive file selection and location targeting.
 */
public interface DiffNavigationTarget {

    /**
     * Navigates to a specific file by its index.
     *
     * @param fileIndex The index of the file in the comparison list.
     */
    void navigateToFile(int fileIndex);

    /**
     * Navigates to a specific file and scrolls to a particular line number.
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
