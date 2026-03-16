package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import java.util.Set;

public interface ProjectTreeHost extends IConsoleIO {
    default ContextManager getContextManager() {
        throw new UnsupportedOperationException();
    }

    default void openFragmentPreview(ContextFragment fragment) {
        throw new UnsupportedOperationException();
    }

    default void runTests(Set<ProjectFile> testFiles) throws InterruptedException {
        throw new UnsupportedOperationException();
    }

    default void addFileHistoryTab(ProjectFile file) {
        throw new UnsupportedOperationException();
    }
}
