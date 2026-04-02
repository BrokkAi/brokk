package ai.brokk.analyzer;

import ai.brokk.project.AbstractProject;
import ai.brokk.project.IProject;
import java.nio.file.Path;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;

@NullMarked
public interface FrameworkTemplate {

    String name();

    String internalName();

    default Path getStoragePath(IProject project) {
        return project.getRoot()
                .resolve(AbstractProject.BROKK_DIR)
                .resolve("code_intelligence")
                .resolve("frameworks")
                .resolve(internalName().toLowerCase(Locale.ROOT) + Language.ANALYZER_STATE_SUFFIX);
    }
}
