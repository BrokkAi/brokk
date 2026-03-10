package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.executor.jobs.JobEvent;
import ai.brokk.executor.jobs.JobSpec;
import ai.brokk.executor.jobs.JobStatus;
import ai.brokk.tools.diagnostics.JobDiagnosticsLoader;
import ai.brokk.tools.diagnostics.JobTimeline;
import ai.brokk.tools.diagnostics.PhaseTimeline;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for JobDiagnosticsLoader.
 */
public class JobDiagnosticsLoaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void testLoadJobsAndPhases(@TempDir Path tempDir) throws Exception {
        Path storeRoot = tempDir.resolve("store");
        Files.createDirectories(storeRoot);

        Path jobsDir = storeRoot.resolve("jobs");
        Files.createDirectories(jobsDir);

        // Job 1: pre-scan notifications -> expect SCAN phase
        String job1 = "job-scan";
        Path j1 = jobsDir.resolve(job1);
        Files.createDirectories(j1);
        JobSpec spec1 = new JobSpec(
                "input1",
                true,
                true,
                "planner-a",
                null,
                null,
                true,
                Map.of("mode", "ASK"),
                null,
                null,
                "HIGH",
                null,
                0.7,
                null,
                false,
                JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);
        MAPPER.writeValue(j1.resolve("meta.json").toFile(), spec1);
        JobStatus status1 = new JobStatus(job1, "COMPLETED", 1000L, 3000L, 100, null, null, Map.of("lastSeq", "2"));
        MAPPER.writeValue(j1.resolve("status.json").toFile(), status1);

        // events.jsonl with scan start and complete
        String ev1 = MAPPER.writeValueAsString(
                new JobEvent(1, 1100L, "NOTIFICATION", Map.of("message", "Brokk Context Engine: analyzing repository context...")));
        String ev2 = MAPPER.writeValueAsString(new JobEvent(
                2, 1200L, "NOTIFICATION", Map.of("message", "Brokk Context Engine: complete — contextual insights added to Workspace.")));
        Files.writeString(
                j1.resolve("events.jsonl"),
                ev1 + "\n" + ev2 + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);

        // Job 2: command_result tests -> expect EXECUTION phase
        String job2 = "job-exec";
        Path j2 = jobsDir.resolve(job2);
        Files.createDirectories(j2);
        JobSpec spec2 = new JobSpec(
                "input2",
                true,
                true,
                "planner-b",
                null,
                null,
                false,
                Map.of("mode", "ARCHITECT"),
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);
        MAPPER.writeValue(j2.resolve("meta.json").toFile(), spec2);
        JobStatus status2 = new JobStatus(job2, "COMPLETED", 2000L, 5000L, 100, null, null, Map.of());
        MAPPER.writeValue(j2.resolve("status.json").toFile(), status2);

        // COMMAND_RESULT event for tests
        Map<String, Object> cmdData = Map.of(
                "stage",
                "tests",
                "command",
                "run-tests",
                "attempt",
                1,
                "skipped",
                false,
                "success",
                false,
                "output",
                "failure");
        String ev21 = MAPPER.writeValueAsString(new JobEvent(1, 2100L, "COMMAND_RESULT", cmdData));
        Files.writeString(j2.resolve("events.jsonl"), ev21 + "\n", StandardOpenOption.CREATE, StandardOpenOption.WRITE);

        // Job 3: no events -> expect UNKNOWN phase
        String job3 = "job-empty";
        Path j3 = jobsDir.resolve(job3);
        Files.createDirectories(j3);
        JobSpec spec3 = new JobSpec(
                "input3",
                true,
                true,
                "planner-c",
                null,
                null,
                false,
                Map.of("mode", "ARCHITECT"),
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS);
        MAPPER.writeValue(j3.resolve("meta.json").toFile(), spec3);
        // no status, no events

        // Create a Brokk debug log with garbage lines to ensure parsing does not throw
        Path brokkDir = tempDir.resolve(".brokk");
        Files.createDirectories(brokkDir);
        Files.writeString(
                brokkDir.resolve("debug.log"),
                "This is some unrelated log line\nAnother line\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        Files.writeString(
                brokkDir.resolve("debug.log.1"),
                "rotated log content\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);

        // Run loader
        List<JobTimeline> jobs = JobDiagnosticsLoader.load(storeRoot, brokkDir);
        assertNotNull(jobs);

        // Map by jobId for easy assertions
        var byId = jobs.stream().collect(Collectors.toMap(JobTimeline::jobId, jt -> jt));

        // job1 checks
        assertTrue(byId.containsKey(job1));
        JobTimeline jt1 = byId.get(job1);
        assertEquals("ASK", jt1.mode());
        assertNotNull(jt1.modelConfig());
        assertEquals("planner-a", jt1.modelConfig().name());
        assertNotNull(jt1.reasoningOverrides());
        assertEquals("HIGH", jt1.reasoningOverrides().reasoningLevel());
        // phases should contain a SCAN phase
        boolean hasScan = jt1.phases().stream().anyMatch(p -> "SCAN".equals(p.type()));
        assertTrue(hasScan);
        PhaseTimeline scanPhase = jt1.phases().stream()
                .filter(p -> "SCAN".equals(p.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(1100L, scanPhase.startTime());
        assertEquals(1200L, scanPhase.endTime());

        // job2 checks
        assertTrue(byId.containsKey(job2));
        JobTimeline jt2 = byId.get(job2);
        assertEquals("ARCHITECT", jt2.mode());
        assertNotNull(jt2.modelConfig());
        assertEquals("planner-b", jt2.modelConfig().name());
        // phases should contain EXECUTION for tests
        boolean hasExec = jt2.phases().stream().anyMatch(p -> "EXECUTION".equals(p.type()));
        assertTrue(hasExec);
        PhaseTimeline exec = jt2.phases().stream()
                .filter(p -> "EXECUTION".equals(p.type()))
                .findFirst()
                .orElseThrow();
        // execution phase anchored by COMMAND_RESULT timestamp
        assertEquals(2100L, exec.startTime());

        // job3 checks - unknown fallback
        assertTrue(byId.containsKey(job3));
        JobTimeline jt3 = byId.get(job3);
        assertNotNull(jt3.phases());
        assertEquals(1, jt3.phases().size());
        assertEquals("UNKNOWN", jt3.phases().get(0).type());
    }
}
