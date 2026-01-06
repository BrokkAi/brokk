package ai.brokk.difftool.ui;

import ai.brokk.analyzer.ProjectFile;
import org.jspecify.annotations.NullMarked;

/**
 * Primary interface for external components (search results, code review links, etc.) 
 * to drive navigation within a diff view using domain objects.
 */
@NullMarked
public interface DiffProjectFileNavigationTarget {

    /**
     * Navigates to a specific file.
     *
     * @param file The ProjectFile to navigate to.
     */
    void navigateToFile(ProjectFile file);

    /**
     * Navigates to a specific file and scrolls to a particular line number.
     *
     * @param file       The ProjectFile to navigate to.
     * @param lineNumber The 1-based line number to navigate to.
     */
    void navigateToLocation(ProjectFile file, int lineNumber);
}
