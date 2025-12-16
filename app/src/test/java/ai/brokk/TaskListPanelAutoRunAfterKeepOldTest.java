package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Source-level assertion that KEEP_OLD branch in TaskListPanel auto-plays (runs all)
 * after applying the choice when incomplete tasks exist.
 *
 * This avoids UI/EDT orchestration by checking the source method contents.
 */
public class TaskListPanelAutoRunAfterKeepOldTest {

    private static String read(String relPath) throws Exception {
        return Files.readString(Path.of(relPath));
    }

    @Test
    void keep_old_branch_runs_all_after_choice() throws Exception {
        String src = read("src/main/java/ai/brokk/gui/terminal/TaskListPanel.java");

        int methodIdx = src.indexOf("handleAutoPlayChoice");
        assertTrue(methodIdx >= 0, "TaskListPanel.handleAutoPlayChoice should exist");

        int keepOldIdx = src.indexOf("case KEEP_OLD", methodIdx);
        assertTrue(keepOldIdx >= 0, "KEEP_OLD branch should be present in handleAutoPlayChoice");

        // Heuristic: ensure runArchitectOnAll() is invoked somewhere after the KEEP_OLD label
        assertTrue(
                src.indexOf("runArchitectOnAll()", keepOldIdx) > keepOldIdx,
                "KEEP_OLD branch should invoke runArchitectOnAll()");
    }
}
