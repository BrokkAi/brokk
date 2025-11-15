package ai.brokk.gui;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import org.jetbrains.annotations.Nullable;

/**
 * Utility class for extracting ProjectFile instances from context fragments.
 * Handles PathFragment types that contain ProjectFile instances (ProjectPathFragment, GitFileFragment).
 * PathFragments containing ExternalFile or other BrokkFile types return null.
 */
public class FragmentFileExtractor {

    /**
     * Extracts a ProjectFile from a context fragment if it contains one.
     * Only PathFragment types can contain files, and only some contain ProjectFile specifically
     * (vs ExternalFile or other BrokkFile types).
     *
     * @param fragment The context fragment to extract from
     * @return The ProjectFile if the fragment is a PathFragment containing a ProjectFile, null otherwise
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
