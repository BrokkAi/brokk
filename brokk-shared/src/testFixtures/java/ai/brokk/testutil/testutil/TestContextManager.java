package ai.brokk.testutil;

import ai.brokk.IContextManager;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.project.ICoreProject;
import java.util.Objects;

/**
 * Minimal {@link IContextManager} implementation for tests in {@code :brokk-shared}.
 *
 * <p>App-only services (tool registry, LLM wiring, UI notifications, etc.) intentionally do not exist here.
 */
public final class TestContextManager implements IContextManager {
    private final ICoreProject project;
    private final IAnalyzer analyzer;

    public TestContextManager(ICoreProject project, IAnalyzer analyzer) {
        this.project = Objects.requireNonNull(project);
        this.analyzer = Objects.requireNonNull(analyzer);
    }

    @Override
    public ICoreProject getProject() {
        return project;
    }

    @Override
    public IAnalyzer getAnalyzerUninterrupted() {
        return analyzer;
    }
}
