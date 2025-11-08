package ai.brokk.gui;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import org.jetbrains.annotations.Nullable;

/**
 * Utility class for extracting ProjectFile instances from context fragments.
 * Handles the various fragment types and their file representations.
 */
public class FragmentFileExtractor {

    /**
     * Extracts a ProjectFile from a context fragment if it contains one.
     * Handles both PathFragments and ComputedFragments that are also PathFragments.
     *
     * @param fragment The context fragment to extract from
     * @return The ProjectFile if one exists, null otherwise
     */
    @Nullable
    public static ProjectFile extractProjectFile(ContextFragment fragment) {
        if (fragment instanceof ContextFragment.PathFragment pf) {
            var file = pf.file();
            if (file instanceof ProjectFile projectFile) {
                return projectFile;
            }
        }
        return null;
    }
}
