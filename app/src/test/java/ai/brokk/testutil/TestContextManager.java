package ai.brokk.testutil;

import ai.brokk.IAnalyzerWrapper;
import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.git.TestRepo;
import ai.brokk.project.IProject;
import ai.brokk.tasks.TaskList;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

/**
 * Lightweight Test IContextManager used in unit tests.
 *
 * <p>Provides a quick AnalyzerWrapper backed by a TaskRunner that immediately returns the MockAnalyzer. This avoids
 * triggering expensive analyzer build logic while satisfying callers (CodeAgent) that expect an AnalyzerWrapper to
 * exist and support pause()/resume()/get().
 */
public final class TestContextManager implements IContextManager {
    private final IProject project;
    private final AtomicReference<IAnalyzer> analyzerRef;
    private final TestRepo repo;
    private final Set<ProjectFile> editableFiles;
    private final IConsoleIO consoleIO;
    private final TestService stubService;
    private Context liveContext;

    // Test-friendly AnalyzerWrapper that uses a "quick runner" to return the mockAnalyzer immediately.
    private final IAnalyzerWrapper analyzerWrapper;

    public TestContextManager(Path projectRoot, IConsoleIO consoleIO) {
        this(new TestProject(projectRoot, Languages.JAVA), consoleIO, Set.of(), new TestAnalyzer());
    }

    public TestContextManager(Path projectRoot, IConsoleIO consoleIO, IAnalyzer analyzer) {
        this(
                analyzer instanceof TestAnalyzer ? new TestProject(projectRoot, Languages.JAVA) : analyzer.getProject(),
                consoleIO,
                Set.of(),
                analyzer);
    }

    public TestContextManager(
            IProject project, IConsoleIO consoleIO, Set<ProjectFile> editableFiles, IAnalyzer analyzer) {
        this.project = project;
        this.analyzerRef = new AtomicReference<>(analyzer);
        this.editableFiles = new HashSet<>(editableFiles);

        this.repo = new TestRepo(project.getRoot());
        this.consoleIO = consoleIO;
        this.stubService = new TestService(this.project);
        this.liveContext = new Context(this).addFragments(toPathFragments(editableFiles));

        this.analyzerWrapper = new IAnalyzerWrapper() {
            @Override
            public IAnalyzer get() {
                return analyzerRef.get();
            }

            @Override
            public @Nullable IAnalyzer getNonBlocking() {
                return analyzerRef.get();
            }

            @Override
            public CompletableFuture<IAnalyzer> updateFiles(Set<ProjectFile> relevantFiles) {
                analyzerRef.set(analyzerRef.get().update(relevantFiles));
                return CompletableFuture.completedFuture(analyzerRef.get());
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
    public IProject getProject() {
        return project;
    }

    @Override
    public TestRepo getRepo() {
        return repo;
    }

    public void addEditableFile(ProjectFile file) {
        liveContext = liveContext.addFragments(new ContextFragment.ProjectPathFragment(file, this));
    }

    @Override
    public IAnalyzerWrapper getAnalyzerWrapper() {
        return analyzerWrapper;
    }

    @Override
    public IAnalyzer getAnalyzerUninterrupted() {
        return analyzerRef.get();
    }

    @Override
    public IAnalyzer getAnalyzer() {
        return analyzerRef.get();
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
    public TestService getService() {
        return stubService;
    }

    @Override
    public StreamingChatModel getCodeModel() {
        return null;
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

    @Override
    public Context createOrReplaceTaskList(Context context, List<String> tasks) {
        // Strip whitespace-only entries
        var cleaned =
                tasks.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (cleaned.isEmpty()) {
            // Clear task list when nothing valid is provided
            return context.withTaskList(new TaskList.TaskListData(List.of()), "Task list cleared");
        }
        var items = cleaned.stream()
                .map(t -> new TaskList.TaskItem(t, t, false)) // title=text, done=false
                .toList();
        return context.withTaskList(new TaskList.TaskListData(items), "Task list replaced");
    }

    private final ExecutorService backgroundTasks = Executors.newCachedThreadPool();

    @Override
    public ExecutorService getBackgroundTasks() {
        return backgroundTasks;
    }
}
