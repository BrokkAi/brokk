package io.github.jbellis.brokk.testutil;

import io.github.jbellis.brokk.AnalyzerWrapper;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.git.InMemoryRepo;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Lightweight Test IContextManager used in unit tests.
 *
 * <p>Provides a quick AnalyzerWrapper backed by a TaskRunner that immediately returns the MockAnalyzer. This avoids
 * triggering expensive analyzer build logic while satisfying callers (CodeAgent) that expect an AnalyzerWrapper to
 * exist and support pause()/resume()/get().
 */
public final class TestContextManager implements IContextManager {
    private final TestProject project;
    private final MockAnalyzer mockAnalyzer;
    private final InMemoryRepo inMemoryRepo;
    private final Set<ProjectFile> editableFiles = new HashSet<>();
    private final Set<ProjectFile> readonlyFiles = new HashSet<>();
    private final IConsoleIO consoleIO;
    private final TestService stubService;
    private final Context liveContext;

    // Test-friendly AnalyzerWrapper that uses a "quick runner" to return the mockAnalyzer immediately.
    private final AnalyzerWrapper analyzerWrapper;

    public TestContextManager(Path projectRoot, IConsoleIO consoleIO) {
        this(new TestProject(projectRoot, Languages.JAVA), consoleIO);
    }

    public TestContextManager(TestProject project, IConsoleIO consoleIO) {
        this.project = project;
        this.mockAnalyzer = new MockAnalyzer(project.getRoot());
        this.inMemoryRepo = new InMemoryRepo();
        this.consoleIO = consoleIO;
        this.stubService = new TestService(this.project);
        this.liveContext = new Context(this, "Test context");

        // Quick TaskRunner that never invokes the provided Callable; it returns a completed future
        // containing the mockAnalyzer. This prevents AnalyzerWrapper from performing real work during tests.
        ContextManager.TaskRunner quickRunner = new ContextManager.TaskRunner() {
            @Override
            public <T> Future<T> submit(String taskDescription, Callable<T> task) {
                CompletableFuture<T> f = new CompletableFuture<>();
                @SuppressWarnings("unchecked")
                T cast = (T) mockAnalyzer;
                f.complete(cast);
                return f;
            }
        };

        this.analyzerWrapper = new AnalyzerWrapper(this.project, quickRunner, /*listener=*/ null, this.consoleIO);
    }

    public TestContextManager(Path tempDir, Set<String> files) {
        this(tempDir, new TestConsoleIO());
        for (var filename : files) {
            addEditableFile(new ProjectFile(tempDir, filename));
        }
    }

    @Override
    public TestProject getProject() {
        return project;
    }

    @Override
    public InMemoryRepo getRepo() {
        return inMemoryRepo;
    }

    @Override
    public Set<ProjectFile> getFilesInContext() {
        return new HashSet<>(editableFiles);
    }

    public void addEditableFile(ProjectFile file) {
        this.editableFiles.add(file);
        this.readonlyFiles.remove(file); // Cannot be both
    }

    public MockAnalyzer getMockAnalyzer() {
        return mockAnalyzer;
    }

    @Override
    public AnalyzerWrapper getAnalyzerWrapper() {
        return analyzerWrapper;
    }

    @Override
    public IAnalyzer getAnalyzerUninterrupted() {
        return mockAnalyzer;
    }

    @Override
    public IAnalyzer getAnalyzer() {
        return mockAnalyzer;
    }

    @Override
    public IConsoleIO getIo() {
        if (consoleIO == null) {
            // Fallback for existing tests that don't pass IConsoleIO,
            // though the interface default would throw UnsupportedOperationException.
            // Consider making IConsoleIO mandatory in constructor if all tests are updated.
            throw new UnsupportedOperationException("IConsoleIO not provided to TestContextManager");
        }
        return consoleIO;
    }

    @Override
    public Context liveContext() {
        if (liveContext == null) {
            throw new UnsupportedOperationException(
                    "liveContext requires IConsoleIO to be provided in TestContextManager constructor");
        }
        return liveContext;
    }

    @Override
    public Context topContext() {
        return liveContext().freeze();
    }

    @Override
    public Service getService() {
        return stubService;
    }

    public EditBlockParser getParserForWorkspace() {
        return EditBlockParser.instance;
    }
}
