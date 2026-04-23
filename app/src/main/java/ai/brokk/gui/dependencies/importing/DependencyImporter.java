package ai.brokk.gui.dependencies.importing;

import ai.brokk.analyzer.Language;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.dependencies.DependenciesPanel;
import ai.brokk.project.ICoreProject;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * GUI-only dependency import service.
 *
 * <p>Core analyzer code lives in brokk-shared. The GUI layer owns "import" because it needs Chrome,
 * background tasks, and user notifications.
 */
public interface DependencyImporter {

    Language.ImportSupport getImportSupport(Language language);

    List<Language.DependencyCandidate> listDependencyPackages(ICoreProject project, Language language);

    boolean importDependency(
            Chrome chrome,
            Language language,
            Language.DependencyCandidate pkg,
            @Nullable DependenciesPanel.DependencyLifecycleListener lifecycle);

    boolean isAnalyzed(ICoreProject project, Language language, Path pathToImport);
}
