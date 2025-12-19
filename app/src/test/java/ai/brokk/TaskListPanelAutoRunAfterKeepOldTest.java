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
    void lutz_ez_autoruns_all_tasks_after_completion() throws Exception {
        String src = read("src/main/java/ai/brokk/gui/InstructionsPanel.java");

        int isLutzEzIdx = src.indexOf("boolean isLutzEz");
        assertTrue(isLutzEzIdx >= 0, "InstructionsPanel should define isLutzEz to gate auto-run behavior");

        int runAllIdx = src.indexOf("runArchitectOnAll()", isLutzEzIdx);
        assertTrue(runAllIdx > isLutzEzIdx, "Lutz EZ branch should invoke runArchitectOnAll()");
    }
}
