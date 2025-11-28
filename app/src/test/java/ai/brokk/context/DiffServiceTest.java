package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IContextManager;
import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiffServiceTest {

    @TempDir
    Path tempDir;

    private IContextManager contextManager;

    @BeforeEach
    void setup() {
        contextManager = new TestContextManager(tempDir, new NoOpConsoleIO());
        ContextFragment.setMinimumId(1);
    }

    @Test
    void diffs_only_for_project_path_fragments() throws Exception {
        // Arrange old content
        var pf = new ProjectFile(tempDir, "src/File1.txt");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "line1\n");

        var oldFrag = new ContextFragment.ProjectPathFragment(pf, contextManager);
        // Pre-compute and cache old content
        oldFrag.text().await(Duration.ofSeconds(2));

        var oldCtx = new Context(
                contextManager, List.of(oldFrag), List.of(), null, CompletableFuture.completedFuture("old"));

        // Mutate file for new context
        Files.writeString(pf.absPath(), "line1\nline2\n");

        var newFrag = new ContextFragment.ProjectPathFragment(pf, contextManager);
        var newCtx = new Context(
                contextManager, List.of(newFrag), List.of(), null, CompletableFuture.completedFuture("new"));

        // Act
        var diffs = DiffService.computeDiff(newCtx, oldCtx);

        // Assert
        assertEquals(1, diffs.size(), "Expected a single diff for the changed project file");
        var de = diffs.getFirst();
        assertEquals(ContextFragment.FragmentType.PROJECT_PATH, de.fragment().getType());
        assertEquals(1, de.linesAdded(), "Expected exactly one added line");
    }

    @Test
    void virtual_fragments_are_excluded_from_diff() {
        var sfOld = new ContextFragment.StringFragment(
                contextManager, "old text", "desc", SyntaxConstants.SYNTAX_STYLE_NONE);
        var oldCtx =
                new Context(contextManager, List.of(sfOld), List.of(), null, CompletableFuture.completedFuture("old"));

        var sfNew = new ContextFragment.StringFragment(
                contextManager, "new text", "desc", SyntaxConstants.SYNTAX_STYLE_NONE);
        var newCtx =
                new Context(contextManager, List.of(sfNew), List.of(), null, CompletableFuture.completedFuture("new"));

        var diffs = DiffService.computeDiff(newCtx, oldCtx);
        assertEquals(0, diffs.size(), "Virtual fragments (StringFragment) should be excluded from diffs");
    }

    @Test
    void external_path_fragments_are_excluded_from_diff() throws Exception {
        var extPath = tempDir.resolve("ext.txt");
        Files.writeString(extPath, "v1");
        var extFile = new ExternalFile(extPath);

        var oldFrag = new ContextFragment.ExternalPathFragment(extFile, contextManager);
        var oldCtx = new Context(
                contextManager, List.of(oldFrag), List.of(), null, CompletableFuture.completedFuture("old"));

        Files.writeString(extPath, "v2");
        var newFrag = new ContextFragment.ExternalPathFragment(extFile, contextManager);
        var newCtx = new Context(
                contextManager, List.of(newFrag), List.of(), null, CompletableFuture.completedFuture("new"));

        var diffs = DiffService.computeDiff(newCtx, oldCtx);
        assertEquals(
                0, diffs.size(), "External path diffs should be excluded (only editable project files are diffed)");
    }

    @Test
    void read_only_project_path_fragment_is_excluded() throws Exception {
        // Arrange old content
        var pf = new ProjectFile(tempDir, "src/ReadOnly.java");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "class A {}\n");

        var oldFrag = new ContextFragment.ProjectPathFragment(pf, contextManager);
        // Seed cache for old content
        oldFrag.text().await(Duration.ofSeconds(2));

        var oldCtx = new Context(
                contextManager, List.of(oldFrag), List.of(), null, CompletableFuture.completedFuture("old"));

        // Mutate file for new context
        Files.writeString(pf.absPath(), "class A {}\nclass B {}\n");

        var newFrag = new ContextFragment.ProjectPathFragment(pf, contextManager);
        var newCtx = new Context(
                contextManager, List.of(newFrag), List.of(), null, CompletableFuture.completedFuture("new"));

        // Mark the new fragment as read-only in the new context
        newCtx = newCtx.setReadonly(newFrag, true);

        var diffs = DiffService.computeDiff(newCtx, oldCtx);
        assertEquals(0, diffs.size(), "Read-only project path fragments should be excluded from diffs");
    }
}
