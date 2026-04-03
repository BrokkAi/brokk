package ai.brokk.analyzer;

import ai.brokk.gui.Chrome;
import ai.brokk.gui.dependencies.DependenciesPanel;
import org.jetbrains.annotations.Nullable;

/**
 * Extension interface for languages that support importing external dependencies
 * through the GUI. Separates GUI-dependent import logic from the core
 * Language interface in brokk-shared.
 */
public interface DependencyImportable extends Language {

    /**
     * Perform the actual import for a selected dependency package.
     *
     * @return true if an import was started, false otherwise.
     */
    boolean importDependency(
            Chrome chrome, Language.DependencyCandidate pkg, @Nullable DependenciesPanel.DependencyLifecycleListener lifecycle);
}
