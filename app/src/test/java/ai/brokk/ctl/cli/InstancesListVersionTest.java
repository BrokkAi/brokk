package ai.brokk.ctl.cli;

import ai.brokk.ctl.CtlConfigPaths;
import ai.brokk.ctl.InstanceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CLI gating on brokkctl major version mismatches.
 */
public class InstancesListVersionTest {

    @Test
    public void testCliGatesMajorMismatchUnlessAllowed(@TempDir Path tempDir) throws Exception {
        CtlConfigPaths cfg = CtlConfigPaths.forBaseConfigDir(tempDir);
        Path instDir = cfg.ensureInstancesDirExists();

        // create an instance record whose brokkctlVersion major differs from client (client = 0.x)
        InstanceRecord rec = new InstanceRecord(
                "inst-major-mismatch",
                Integer.valueOf(123),
                "127.0.0.1:0",
                List.of(),
                "1.0.0",
                System.currentTimeMillis(),
                System.currentTimeMillis()
        );

        Path file = instDir.resolve(rec.instanceId + ".json");
        Files.writeString(file, rec.toJson());

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        // Without --allow-incompatible expect error (exit code 2)
        int code = BrokkCtlMain.run(new String[] {"instances", "list", "--config-dir", tempDir.toString()}, new PrintStream(outBuf), new PrintStream(errBuf));
        assertEquals(2, code, "Expected CLI to gate on major version mismatch by default");
        String errOut = errBuf.toString();
        assertTrue(errOut.contains("Incompatible brokkctl major version") || errOut.contains("Incompatible"), "error should mention incompatibility");

        // With flag, should succeed
        outBuf.reset();
        errBuf.reset();
        code = BrokkCtlMain.run(new String[] {"instances", "list", "--config-dir", tempDir.toString(), "--allow-incompatible"}, new PrintStream(outBuf), new PrintStream(errBuf));
        assertEquals(0, code, "Expected CLI to allow incompatible when --allow-incompatible is provided");
        String out = outBuf.toString();
        assertTrue(out.contains("\"instances\""), "Output should contain instances envelope");
    }
}
