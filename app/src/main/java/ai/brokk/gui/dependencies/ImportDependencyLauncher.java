package ai.brokk.gui.dependencies;

import ai.brokk.gui.Chrome;
import ai.brokk.gui.dialogs.ImportDependencyDialog;
import java.awt.Window;

public interface ImportDependencyLauncher {
    void show(Chrome chrome, Window owner, DependenciesPanel.DependencyLifecycleListener listener);

    class DefaultImportDependencyLauncher implements ImportDependencyLauncher {
        @Override
        public void show(Chrome chrome, Window owner, DependenciesPanel.DependencyLifecycleListener listener) {
            ImportDependencyDialog.show(chrome, owner, listener);
        }
    }
}
