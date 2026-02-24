package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

public class CommandResultEventTest {

    @Test
    void testToJsonTruncatesOutput() {
        String veryLongOutput = "a".repeat(30_000);
        JobRunner.CommandResultEvent event = new JobRunner.CommandResultEvent(
                "test-stage", "test-command", 1, false, null, true, veryLongOutput, null);

        Map<String, Object> json = event.toJson();

        String output = (String) json.get("output");
        assertNotNull(output);
        assertTrue(output.length() < veryLongOutput.length());
        assertEquals(Boolean.TRUE, json.get("outputTruncated"));
    }
}
