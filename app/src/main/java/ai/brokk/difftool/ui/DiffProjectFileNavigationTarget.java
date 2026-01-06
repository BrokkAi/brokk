package ai.brokk.difftool.ui;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.util.ReviewParser;
import org.jspecify.annotations.NullMarked;

/**
 * Primary interface for external components (search results, code review links, etc.)
 * to drive navigation within a diff view using domain objects.
 */
@NullMarked
public interface DiffProjectFileNavigationTarget {

    void navigateToFile(int fileIndex);

    /**
     * Navigates to a specific file.
     *
     * @param file The ProjectFile to navigate to.
     */
    void navigateToFile(ProjectFile file);

    void navigateToLocation(ProjectFile file, int lineNumber, ReviewParser.DiffSide side);
}
