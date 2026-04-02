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

    String FRAMEWORKS_DIR = "frameworks";
    String LEGACY_TEMPLATE_DIR = "template";

    default Path getStoragePath(IProject project) {
        return project.getRoot()
                .resolve(AbstractProject.BROKK_DIR)
                .resolve(AbstractProject.CODE_INTELLIGENCE_DIR)
                .resolve(FRAMEWORKS_DIR)
                .resolve(internalName().toLowerCase(Locale.ROOT) + Language.ANALYZER_STATE_SUFFIX);
    }
}
