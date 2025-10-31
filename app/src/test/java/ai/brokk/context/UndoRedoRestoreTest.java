package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.nio.file.Path;
import java.util.List;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class UndoRedoRestoreTest {

    @TempDir
    Path tempDir;

    @Test
    void undoRestoresSnapshotContentFromCachedComputedValues() throws Exception {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());

        // 1) Create a file with initial contents and add it to the context
        var pf = new ProjectFile(tempDir, "sample.txt");
        pf.write("v1");

        var live =
                new Context(cm, "Initial").addPathFragments(List.of(new ContextFragment.ProjectPathFragment(pf, cm)));

        // 2) Build history with the initial snapshot
        var history = new ContextHistory(live);

        // 3) Push a second snapshot (e.g., adding a virtual fragment) to enable undo
        history.push(ctx -> ctx.addVirtualFragment(
                new ContextFragment.StringFragment(cm, "hello", "desc", SyntaxConstants.SYNTAX_STYLE_NONE)));

        // 4) Modify the file externally to a different value
        pf.write("v2");

        // 5) Undo should restore the workspace file to the cached snapshot content ("v1")
        var io = new NoOpConsoleIO();
        var project = cm.getProject();
        var result = history.undo(1, io, project);
        // Verify workspace content is restored to the snapshot value
        var restored = pf.read().orElse("");
        assertEquals("v1", restored, "Undo should restore file to snapshot content, not retain external changes");
    }
}
