package ai.brokk.tools;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageRankBenchmarkTest {

    @Test
    void sanity_createsRunnerInstance() {
        PageRankBenchmark benchmark = new PageRankBenchmark();
        assertNotNull(benchmark);
    }

    @Test
    void help_displaysUsageInformation() {
        PageRankBenchmark benchmark = new PageRankBenchmark();
        CommandLine cmd = new CommandLine(benchmark);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--help");

        assertEquals(0, exitCode);
        String output = sw.toString();
        assertTrue(output.contains("Usage: PageRankBenchmark"));
        assertTrue(output.contains("--warm-up-iterations"));
        assertTrue(output.contains("--iterations"));
        assertTrue(output.contains("--files"));
    }

    @Test
    void minimalRun_executesWithoutException() {
        // Run a tiny scenario to verify plumbing (1 file, no warmups, 1 iteration)
        PageRankBenchmark benchmark = new PageRankBenchmark();
        CommandLine cmd = new CommandLine(benchmark);

        int exitCode = cmd.execute(
                "--files", "2",
                "--warm-up-iterations", "0",
                "--iterations", "1",
                "--sparse-commit-count", "1",
                "--dense-commit-count", "1",
                "--scenario", "sparse"
        );

        assertEquals(0, exitCode);
    }
}
