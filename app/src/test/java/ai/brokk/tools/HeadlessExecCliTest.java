package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HeadlessExecCliTest {

    @Test
    void emptyReasoningLevelIsAccepted_butInvalidTemperatureFailsParsing() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        try (PrintStream capturedErr = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            System.setErr(capturedErr);

            String[] args = {"--planner-model", "gpt-5", "--reasoning-level", "", "--temperature", "not-a-number"};

            int exitCode = HeadlessExecCli.runCli(args);
            capturedErr.flush();

            String stderr = errBytes.toString(StandardCharsets.UTF_8);

            assertEquals(1, exitCode);
            assertTrue(stderr.contains("ERROR: Invalid --temperature value: not-a-number"), stderr);
            assertFalse(stderr.toLowerCase().contains("reasoning"), stderr);
        } finally {
            System.setErr(originalErr);
        }
    }
}
