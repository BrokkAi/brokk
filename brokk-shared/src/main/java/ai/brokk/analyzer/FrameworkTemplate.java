package ai.brokk.analyzer;

import java.nio.file.Path;
import java.util.Locale;

import ai.brokk.project.ICoreProject;
import org.jspecify.annotations.NullMarked;

@NullMarked
public interface FrameworkTemplate {

    String name();

    String internalName();

    String FRAMEWORKS_DIR = "frameworks";
    String LEGACY_TEMPLATE_DIR = "template";

    default Path getStoragePath(Path project) {
        return project.getRoot()
                .resolve(ICoreProject.BROKK_DIR)
                .resolve(ICoreProject.CODE_INTELLIGENCE_DIR)
                .resolve(FRAMEWORKS_DIR)
                .resolve(internalName().toLowerCase(Locale.ROOT) + Language.ANALYZER_STATE_SUFFIX);
    }
}
