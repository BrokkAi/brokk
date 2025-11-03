package ai.brokk.gui.dependencies;

import ai.brokk.gui.dialogs.ImportDependencyDialog;
import java.awt.Window;

/**
 * Abstraction used by DependenciesPanel to launch the import dialog. The interface accepts a
 * DependenciesHost rather than a full Chrome to improve testability.
 */
public interface ImportDependencyLauncher {
    void show(DependenciesHost host, Window owner, DependenciesPanel.DependencyLifecycleListener listener);

    class DefaultImportDependencyLauncher implements ImportDependencyLauncher {
        @Override
        public void show(DependenciesHost host, Window owner, DependenciesPanel.DependencyLifecycleListener listener) {
            // If the host is the production DefaultDependenciesHost we can obtain the underlying Chrome.
            if (host instanceof DependenciesHost.DefaultDependenciesHost ddh) {
                ImportDependencyDialog.show(ddh.getChrome(), owner, listener);
            } else {
                // Best-effort: try to launch using the host's frame as owner and rely on the host for callbacks.
                // The ImportDependencyDialog API requires a Chrome instance; in non-production tests the
                // launcher implementation should be overridden.
                throw new UnsupportedOperationException(
                        "DefaultImportDependencyLauncher requires a DependenciesHost.DefaultDependenciesHost (production). " +
                        "Tests should inject an ImportDependencyLauncher that can operate with the provided host.");
            }
        }
    }
}
