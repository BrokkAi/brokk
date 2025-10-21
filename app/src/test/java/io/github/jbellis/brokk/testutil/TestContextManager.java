package io.github.jbellis.brokk.testutil;

import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.IAnalyzerWrapper;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.git.TestRepo;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import java.io.File;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

/**
 * Lightweight Test IContextManager used in unit tests.
 *
 * <p>Provides a quick AnalyzerWrapper backed by a TaskRunner that immediately returns the MockAnalyzer. This avoids
 * triggering expensive analyzer build logic while satisfying callers (CodeAgent) that expect an AnalyzerWrapper to
 * exist and support pause()/resume()/get().
 */
public final class TestContextManager implements IContextManager {
    private final TestProject project;
    private final IAnalyzer analyzer;
    private final TestRepo repo;
    private final Set<ProjectFile> editableFiles;
    private final Set<ProjectFile> readonlyFiles;
    private final IConsoleIO consoleIO;
    private final TestService stubService;
    private final Context liveContext;

    // Test-friendly AnalyzerWrapper that uses a "quick runner" to return the mockAnalyzer immediately.
    private final IAnalyzerWrapper analyzerWrapper;

    public TestContextManager(Path projectRoot, IConsoleIO consoleIO) {
        this(new TestProject(projectRoot, Languages.JAVA), consoleIO, new HashSet<>(), new TestAnalyzer());
    }

    public TestContextManager(
            TestProject project, IConsoleIO consoleIO, Set<ProjectFile> editableFiles, IAnalyzer analyzer) {
        this.project = project;
        this.analyzer = analyzer;
        this.editableFiles = editableFiles;

        this.readonlyFiles = new HashSet<>();
        this.repo = new TestRepo(project.getRoot());
        this.consoleIO = consoleIO;
        this.stubService = new TestService(this.project);
        this.liveContext = new Context(this, "Test context");

        this.analyzerWrapper = new IAnalyzerWrapper() {
            @Override
            public IAnalyzer get() throws InterruptedException {
                return analyzer;
            }

            @Override
            public @Nullable IAnalyzer getNonBlocking() {
                return analyzer;
            }

            @Override
            public CompletableFuture<IAnalyzer> updateFiles(Set<ProjectFile> relevantFiles) {
                return CompletableFuture.completedFuture(analyzer);
            }
        };
    }

    public TestContextManager(Path projectRoot, Set<String> editableFiles) {
        this(
                new TestProject(projectRoot, Languages.JAVA),
                new TestConsoleIO(),
                new HashSet<>(editableFiles.stream()
                        .map(s -> new ProjectFile(projectRoot, s))
                        .toList()),
                new TestAnalyzer());
    }

    @Override
    public TestProject getProject() {
        return project;
    }

    @Override
    public TestRepo getRepo() {
        return repo;
    }

    @Override
    public Set<ProjectFile> getFilesInContext() {
        return new HashSet<>(editableFiles);
    }

    public void addEditableFile(ProjectFile file) {
        this.editableFiles.add(file);
        this.readonlyFiles.remove(file); // Cannot be both
    }

    public void addReadonlyFile(ProjectFile file) {
        this.readonlyFiles.add(file);
        this.editableFiles.remove(file); // Cannot be both
    }

    @Override
    public IAnalyzerWrapper getAnalyzerWrapper() {
        return analyzerWrapper;
    }

    @Override
    public IAnalyzer getAnalyzerUninterrupted() {
        return analyzer;
    }

    @Override
    public IAnalyzer getAnalyzer() {
        return analyzer;
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

    /**
     * Set a custom model to be returned by getLlm when requesting the quickest model. Used for testing preprocessing
     * behavior.
     */
    public void setQuickestModel(StreamingChatModel model) {
        stubService.setQuickestModel(model);
    }

    public ProjectFile toFile(String relativePath) {
        var trimmed = relativePath.trim();

        // If an absolute-like path is provided (leading '/' or '\'), attempt to interpret it as a
        // project-relative path by stripping the leading slash. If that file exists, return it.
        if (trimmed.startsWith(File.separator)) {
            var candidateRel = trimmed.substring(File.separator.length()).trim();
            var candidate = new ProjectFile(project.getRoot(), candidateRel);
            if (candidate.exists()) {
                return candidate;
            }
            // The path looked absolute (or root-anchored) but does not exist relative to the project.
            // Treat this as invalid to avoid resolving to a location outside the project root.
            throw new IllegalArgumentException(
                    "Filename '%s' is absolute-like and does not exist relative to the project root"
                            .formatted(relativePath));
        }

        return new ProjectFile(project.getRoot(), trimmed);
    }

    private String buildFragmentContent = "";
}
