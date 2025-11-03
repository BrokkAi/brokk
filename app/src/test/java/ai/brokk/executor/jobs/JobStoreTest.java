package ai.brokk.executor.jobs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobStoreTest {

  private JobStore store;

  @BeforeEach
  void setup(@TempDir Path tempDir) throws Exception {
    store = new JobStore(tempDir);
  }

  @Test
  void testCreateOrGetJob_NewJob(@TempDir Path tempDir) throws Exception {
    var store = new JobStore(tempDir);
    var spec = JobSpec.of("session-123", "test task");
    var result = store.createOrGetJob("idem-key-1", spec);

    assertTrue(result.isNewJob());
    assertNotNull(result.jobId());
    
    var loadedSpec = store.loadSpec(result.jobId());
    assertEquals(spec.sessionId(), loadedSpec.sessionId());
    assertEquals(spec.taskInput(), loadedSpec.taskInput());
  }

  @Test
  void testCreateOrGetJob_Idempotency(@TempDir Path tempDir) throws Exception {
    var store = new JobStore(tempDir);
    var spec = JobSpec.of("session-123", "test task");
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
    var spec = JobSpec.of("session-123", "test task");
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
    var spec = JobSpec.of("session-123", "test task");
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
    var spec = JobSpec.of("session-123", "test task");
    var result = store.createOrGetJob("idem-key-1", spec);
    var jobId = result.jobId();

    var initialStatus = store.loadStatus(jobId);
    assertEquals(JobStatus.State.QUEUED.name(), initialStatus.state());

    var runningStatus = initialStatus.withState(JobStatus.State.RUNNING.name()).withProgress(50);
    store.updateStatus(jobId, runningStatus);

    var loaded = store.loadStatus(jobId);
    assertEquals(JobStatus.State.RUNNING.name(), loaded.state());
    assertEquals(50, loaded.progressPercent());
  }

  @Test
  void testWriteReadArtifact(@TempDir Path tempDir) throws Exception {
    var store = new JobStore(tempDir);
    var spec = JobSpec.of("session-123", "test task");
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
    var spec = JobSpec.of("session-123", "test task");
    var result = store.createOrGetJob("idem-key-1", spec);
    var jobId = result.jobId();

    var jobDir = store.getJobDir(jobId);
    assertTrue(Files.exists(jobDir));
    assertTrue(Files.exists(jobDir.resolve("meta.json")));
    assertTrue(Files.exists(jobDir.resolve("status.json")));
    assertTrue(Files.exists(jobDir.resolve("artifacts")));
  }
}
