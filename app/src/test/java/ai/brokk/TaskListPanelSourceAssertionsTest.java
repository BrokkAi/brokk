package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Source-level assertions for TaskListPanel to ensure we wait for model population
 * before triggering actions (fix for issue #2036).
 */
public class TaskListPanelSourceAssertionsTest {

    private static String read(String relPath) throws Exception {
        return Files.readString(Path.of(relPath));
    }

    @Test
    void exposes_runArchitectOnAll() throws Exception {
        String src = read("src/main/java/ai/brokk/gui/terminal/TaskListPanel.java");
        assertTrue(
                src.contains("public void runArchitectOnAll()"),
                "TaskListPanel should expose runArchitectOnAll for running all tasks");
    }
}
