package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobStoreTest {

    private static final String DEFAULT_PLANNER_MODEL = "gpt-5";

    @BeforeEach
    void setup(@TempDir Path tempDir) throws Exception {
        new JobStore(tempDir);
    }

    @Test
    void testCreateOrGetJob_NewJob(@TempDir Path tempDir) throws Exception {
        var store = new JobStore(tempDir);
        var spec = JobSpec.of("test task", DEFAULT_PLANNER_MODEL);
        var result = store.createOrGetJob("idem-key-1", spec);

        assertTrue(result.isNewJob());
        assertNotNull(result.jobId());

        var loadedSpec = store.loadSpec(result.jobId());
        assertEquals(spec.taskInput(), loadedSpec.taskInput());
    }

    @Test
    void testCreateOrGetJob_Idempotency(@TempDir Path tempDir) throws Exception {
        var store = new JobStore(tempDir);
        var spec = JobSpec.of("test task", DEFAULT_PLANNER_MODEL);
        var result1 = store.createOrGetJob("idem-key-1", spec);

        // Same idempotency key should return same job
        var result2 = store.createOrGetJob("idem-key-1", spec);

        assertTrue(result1.isNewJob());
        assertFalse(result2.isNewJob());
        assertEquals(result1.jobId(), result2.jobId());
    }

    @Test
    void testAppendEvent_MonotonicSequence(@TempDir Path tempDir) throws Exception {
        var store = new JobStore(tempDir);
        var spec = JobSpec.of("test task", DEFAULT_PLANNER_MODEL);
        var result = store.createOrGetJob("idem-key-1", spec);
        var jobId = result.jobId();

        var seq1 = store.appendEvent(jobId, JobEvent.of("event1", Map.of("key", "value")));
        var seq2 = store.appendEvent(jobId, JobEvent.of("event2", Map.of("key", "value2")));
        var seq3 = store.appendEvent(jobId, JobEvent.of("event3", null));

        assertEquals(1L, seq1);
        assertEquals(2L, seq2);
        assertEquals(3L, seq3);
    }

    @Test
    void testReadEvents_FilterBySeq(@TempDir Path tempDir) throws Exception {
        var store = new JobStore(tempDir);
        var spec = JobSpec.of("test task", DEFAULT_PLANNER_MODEL);
        var result = store.createOrGetJob("idem-key-1", spec);
        var jobId = result.jobId();

        store.appendEvent(jobId, JobEvent.of("event1", "data1"));
        store.appendEvent(jobId, JobEvent.of("event2", "data2"));
        store.appendEvent(jobId, JobEvent.of("event3", "data3"));

        // Read all events
        var allEvents = store.readEvents(jobId, -1, 0);
        assertEquals(3, allEvents.size());

        // Read events after seq 1
        var afterOne = store.readEvents(jobId, 1, 0);
        assertEquals(2, afterOne.size());
        assertEquals("event2", afterOne.get(0).type());
        assertEquals("event3", afterOne.get(1).type());

        // Read events with limit
        var limited = store.readEvents(jobId, -1, 2);
        assertEquals(2, limited.size());
    }

    @Test
    void testUpdateStatus(@TempDir Path tempDir) throws Exception {
        var store = new JobStore(tempDir);
        var spec = JobSpec.of("test task", DEFAULT_PLANNER_MODEL);
        var result = store.createOrGetJob("idem-key-1", spec);
        var jobId = result.jobId();

        var initialStatus = store.loadStatus(jobId);
        assertEquals(JobStatus.State.QUEUED.name(), initialStatus.state());

        var runningStatus =
                initialStatus.withState(JobStatus.State.RUNNING.name()).withProgress(50);
        store.updateStatus(jobId, runningStatus);

        var loaded = store.loadStatus(jobId);
        assertEquals(JobStatus.State.RUNNING.name(), loaded.state());
        assertEquals(50, loaded.progressPercent());
    }

    @Test
    void testWriteReadArtifact(@TempDir Path tempDir) throws Exception {
        var store = new JobStore(tempDir);
        var spec = JobSpec.of("test task", DEFAULT_PLANNER_MODEL);
        var result = store.createOrGetJob("idem-key-1", spec);
        var jobId = result.jobId();

        var diffContent = "--- a/file.txt\n+++ b/file.txt\n@@ -1 +1 @@\n-old\n+new\n".getBytes();
        store.writeArtifact(jobId, "diff.txt", diffContent);

        var loaded = store.readArtifact(jobId, "diff.txt");
        assertNotNull(loaded);
        assertEquals(diffContent.length, loaded.length);
    }

    @Test
    void testJobDir(@TempDir Path tempDir) throws Exception {
        var store = new JobStore(tempDir);
        var spec = JobSpec.of("test task", DEFAULT_PLANNER_MODEL);
        var result = store.createOrGetJob("idem-key-1", spec);
        var jobId = result.jobId();

        var jobDir = store.getJobDir(jobId);
        assertTrue(Files.exists(jobDir));
        assertTrue(Files.exists(jobDir.resolve("meta.json")));
        assertTrue(Files.exists(jobDir.resolve("status.json")));
        assertTrue(Files.exists(jobDir.resolve("artifacts")));
    }

    @Test
    void testIdempotencyKeyHashing(@TempDir Path tempDir) throws Exception {
        var store = new JobStore(tempDir);
        var spec1 = JobSpec.of("task 1", DEFAULT_PLANNER_MODEL);
        var spec2 = JobSpec.of("task 2", DEFAULT_PLANNER_MODEL);

        // Different idempotency keys should create different jobs
        var result1 = store.createOrGetJob("unique-key-1", spec1);
        var result2 = store.createOrGetJob("unique-key-2", spec2);

        assertTrue(result1.isNewJob());
        assertTrue(result2.isNewJob());
        assertNotEquals(result1.jobId(), result2.jobId());

        // Same key should return same job even with different spec
        var result3 = store.createOrGetJob("unique-key-1", spec2);
        assertFalse(result3.isNewJob());
        assertEquals(result1.jobId(), result3.jobId());
    }

    @Test
    void testSequenceCounterPersistence(@TempDir Path tempDir) throws Exception {
        var store = new JobStore(tempDir);
        var spec = JobSpec.of("test task", DEFAULT_PLANNER_MODEL);
        var result = store.createOrGetJob("idem-key-1", spec);
        var jobId = result.jobId();

        // Append some events
        store.appendEvent(jobId, JobEvent.of("event1", "data1"));
        store.appendEvent(jobId, JobEvent.of("event2", "data2"));
        store.appendEvent(jobId, JobEvent.of("event3", "data3"));

        // Create new JobStore instance with same directory (simulates restart)
        var store2 = new JobStore(tempDir);

        // Next event should continue from sequence 3
        var seq = store2.appendEvent(jobId, JobEvent.of("event4", "data4"));
        assertEquals(4L, seq);

        // Verify all events are readable
        var allEvents = store2.readEvents(jobId, -1, 0);
        assertEquals(4, allEvents.size());
        assertEquals(4L, allEvents.get(3).seq());
    }
}
