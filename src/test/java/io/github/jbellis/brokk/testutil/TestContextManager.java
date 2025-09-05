package io.github.jbellis.brokk.testutil;

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

public final class TestContextManager implements IContextManager {
    private final TestProject project;
    private final MockAnalyzer mockAnalyzer;
    private final InMemoryRepo inMemoryRepo;
    private final Set<ProjectFile> editableFiles = new HashSet<>();
    private final Set<ProjectFile> readonlyFiles = new HashSet<>();
    private final IConsoleIO consoleIO;
    private final TestService stubService;
    private final Context liveContext;

    public TestContextManager(Path projectRoot, IConsoleIO consoleIO) {
        this.project = new TestProject(projectRoot, Language.JAVA);
        this.mockAnalyzer = new MockAnalyzer(projectRoot);
        this.inMemoryRepo = new InMemoryRepo();
        this.consoleIO = consoleIO;
        this.stubService = new TestService(this.project);
        this.liveContext = new Context(this, "Test context");
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
    public Set<ProjectFile> getEditableFiles() {
        return new HashSet<>(editableFiles);
    }

    @Override
    public Set<BrokkFile> getReadonlyProjectFiles() {
        return new HashSet<>(readonlyFiles);
    }

    public void addEditableFile(ProjectFile file) {
        this.editableFiles.add(file);
        this.readonlyFiles.remove(file); // Cannot be both
    }

    public void addReadonlyFile(ProjectFile file) {
        this.readonlyFiles.add(file);
        this.editableFiles.remove(file); // Cannot be both
    }

    public MockAnalyzer getMockAnalyzer() {
        return mockAnalyzer;
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
    public Service getService() {
        return stubService;
    }

    @Override
    public EditBlockParser getParserForWorkspace() {
        return EditBlockParser.getParserFor("");
    }
}
