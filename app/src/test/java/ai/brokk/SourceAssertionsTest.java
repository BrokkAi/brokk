package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Asserts that SearchAgent no longer references appendTaskList.
 * This ensures the tool is hidden from Search flows.
 */
public class SourceAssertionsTest {

    private static String read(String relPath) throws Exception {
        return Files.readString(Path.of(relPath));
    }

    @Test
    void searchAgent_does_not_reference_appendTaskList() throws Exception {
        String src = read("src/main/java/ai/brokk/agents/SearchAgent.java");
        assertFalse(src.contains("appendTaskList("), "SearchAgent must not reference appendTaskList");
    }
}
