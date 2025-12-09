package ai.brokk;

import ai.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class NativeProjectWatchServiceTest {
    private NativeProjectWatchService service;
    private Path tempDir;

    @AfterEach
    public void tearDown() throws Exception {
        if (service != null) {
            service.close();
            service = null;
        }
        if (tempDir != null) {
            try {
                // Best-effort cleanup
                Files.walk(tempDir)
                        .map(Path::toFile)
                        .sorted((a, b) -> -a.compareTo(b))
                        .forEach(f -> f.delete());
            } catch (IOException ignored) {
            } finally {
                tempDir = null;
            }
        }
    }

    @Test
    public void testPauseResumeQueuesEvents() throws Exception {
        tempDir = Files.createTempDirectory("native-watcher-test");
        // Create service but DO NOT start it yet so we can register listeners before warmup.
        service = new NativeProjectWatchService(
                tempDir, /*gitRepoRoot=*/ null, /*globalGitignorePath=*/ null, List.of());

        // Listener state
        CountDownLatch warmup = new CountDownLatch(1);
        AtomicBoolean acceptAfterPause = new AtomicBoolean(false);
        AtomicReference<CountDownLatch> postResumeLatchRef = new AtomicReference<>();
        AtomicReference<IWatchService.EventBatch> received = new AtomicReference<>();
        AtomicInteger filesChangedCount = new AtomicInteger(0);

        // Add listener BEFORE starting the service so warmup notification can't be missed.
        service.addListener(new IWatchService.Listener() {
            @Override
            public void onFilesChanged(IWatchService.EventBatch batch) {
                if (!acceptAfterPause.get()) {
                    // Warmup phase: ensure watcher is active
                    warmup.countDown();
                    return;
                }
                // Count deliveries that occur after we start accepting post-pause events.
                filesChangedCount.incrementAndGet();
                received.set(batch);
                CountDownLatch l = postResumeLatchRef.get();
                if (l != null) {
                    l.countDown();
                }
            }

            @Override
            public void onNoFilesChangedDuringPollInterval() {}
        });

        // Start service with an already-completed future so watcher begins immediately
        service.start(CompletableFuture.completedFuture(null));

        // Warm up: create a file to confirm watcher is fully registered and delivering events
        Path warmupFile = tempDir.resolve("warmup.txt");
        Files.writeString(warmupFile, "warmup");
        boolean warmupDelivered = warmup.await(5, TimeUnit.SECONDS);
        assertTrue(warmupDelivered, "Watcher should deliver a warmup event before pause");

        // Prepare for the real test phase
        CountDownLatch notified = new CountDownLatch(1);
        postResumeLatchRef.set(notified);
        received.set(null);
        acceptAfterPause.set(true);
        filesChangedCount.set(0);

        // Pause the watcher. Wait briefly to allow the native DirectoryWatcher to complete registration
        // so the subsequent file creation will be observed (and therefore buffered) instead of missed.
        Thread.sleep(500);
        service.pause();

        // Create a file that should generate an event while paused
        Path created = tempDir.resolve("file.txt");
        Files.writeString(created, "hello");

        // Small delay and then append to create an additional MODIFY event while still paused.
        Thread.sleep(200);
        Files.writeString(created, "more", StandardOpenOption.APPEND);
        Thread.sleep(200);

        // Wait longer than debounce delay to ensure that if watcher were not paused it would have fired.
        boolean firedWhilePaused = notified.await(1200, TimeUnit.MILLISECONDS);
        assertFalse(firedWhilePaused, "No notification should be delivered while watcher is paused");
        assertNull(received.get(), "No batch should have been delivered while paused");

        // Resume and expect the buffered event to flush
        service.resume();

        boolean deliveredAfterResume = notified.await(5, TimeUnit.SECONDS);
        assertTrue(deliveredAfterResume, "Buffered events should be delivered after resume");

        // Ensure exactly one onFilesChanged occurred after resume
        assertEquals(1, filesChangedCount.get(), "Exactly one onFilesChanged delivery should occur after resume");

        IWatchService.EventBatch batch = received.get();
        assertNotNull(batch, "EventBatch should be non-null after resume");
        assertFalse(batch.files.isEmpty(), "EventBatch should contain files");

        // Expect the created file to be present in the batch.
        ProjectFile expected = new ProjectFile(tempDir, tempDir.relativize(created));
        assertTrue(batch.files.contains(expected), "EventBatch should contain the created file: " + created);
    }
}
