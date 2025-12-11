package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
        service =
                new NativeProjectWatchService(tempDir, /*gitRepoRoot=*/ null, /*globalGitignorePath=*/ null, List.of());

        // Listener state
        AtomicReference<CountDownLatch> postResumeLatchRef = new AtomicReference<>();
        AtomicReference<IWatchService.EventBatch> received = new AtomicReference<>();
        AtomicInteger filesChangedCount = new AtomicInteger(0);

        service.addListener(new IWatchService.Listener() {
            @Override
            public void onFilesChanged(IWatchService.EventBatch batch) {
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

        // Prepare for the test
        CountDownLatch notified = new CountDownLatch(1);
        postResumeLatchRef.set(notified);
        received.set(null);
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

    @Test
    public void testNestedPauseQueuesEvents() throws Exception {
        tempDir = Files.createTempDirectory("native-watcher-test-nested");
        service =
                new NativeProjectWatchService(tempDir, /*gitRepoRoot=*/ null, /*globalGitignorePath=*/ null, List.of());

        // Listener state
        AtomicReference<CountDownLatch> postResumeLatchRef = new AtomicReference<>();
        AtomicReference<IWatchService.EventBatch> received = new AtomicReference<>();
        AtomicInteger filesChangedCount = new AtomicInteger(0);

        service.addListener(new IWatchService.Listener() {
            @Override
            public void onFilesChanged(IWatchService.EventBatch batch) {
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

        service.start(CompletableFuture.completedFuture(null));

        // Prepare for the test
        CountDownLatch notified = new CountDownLatch(1);
        postResumeLatchRef.set(notified);
        received.set(null);
        filesChangedCount.set(0);

        // Nested pause: call pause twice
        Thread.sleep(500);
        service.pause();
        // allow the pause to take effect
        Thread.sleep(100);
        service.pause();

        // Make multiple file changes while fully paused
        Path f1 = tempDir.resolve("a.txt");
        Path f2 = tempDir.resolve("b.txt");
        Files.writeString(f1, "first");
        Thread.sleep(100);
        Files.writeString(f2, "second");
        Thread.sleep(100);
        // append to both to generate additional modify events
        Files.writeString(f1, "more", StandardOpenOption.APPEND);
        Files.writeString(f2, "more", StandardOpenOption.APPEND);

        // Wait longer than debounce delay to ensure no notification while paused
        boolean firedWhilePaused = notified.await(1200, TimeUnit.MILLISECONDS);
        assertFalse(firedWhilePaused, "No notification should be delivered while watcher is paused (nested)");

        // Resume once: still paused because pauseCount was 2 -> 1
        service.resume();

        // Still should not deliver
        boolean firedAfterOneResume = notified.await(1200, TimeUnit.MILLISECONDS);
        assertFalse(
                firedAfterOneResume,
                "No notification should be delivered after a single resume when nested pause remains");

        // Resume again: now pauseCount reaches 0 and queued events should flush
        service.resume();

        boolean deliveredAfterSecondResume = notified.await(5, TimeUnit.SECONDS);
        assertTrue(deliveredAfterSecondResume, "Buffered events should be delivered after final resume");

        // Exactly one delivery after fully resuming
        assertEquals(
                1, filesChangedCount.get(), "Exactly one onFilesChanged delivery should occur after fully resuming");

        IWatchService.EventBatch batch = received.get();
        assertNotNull(batch, "EventBatch should be non-null after fully resuming");
        assertFalse(batch.files.isEmpty(), "EventBatch should contain files after nested resume");

        ProjectFile expected1 = new ProjectFile(tempDir, tempDir.relativize(f1));
        ProjectFile expected2 = new ProjectFile(tempDir, tempDir.relativize(f2));
        assertTrue(batch.files.contains(expected1), "EventBatch should contain the first file: " + f1);
        assertTrue(batch.files.contains(expected2), "EventBatch should contain the second file: " + f2);
    }

    @Test
    public void testPausedDebounceCoalescing() throws Exception {
        tempDir = Files.createTempDirectory("native-watcher-test-debounce");
        service =
                new NativeProjectWatchService(tempDir, /*gitRepoRoot=*/ null, /*globalGitignorePath=*/ null, List.of());

        // Listener state
        AtomicReference<CountDownLatch> postResumeLatchRef = new AtomicReference<>();
        AtomicReference<IWatchService.EventBatch> received = new AtomicReference<>();
        AtomicInteger filesChangedCount = new AtomicInteger(0);

        service.addListener(new IWatchService.Listener() {
            @Override
            public void onFilesChanged(IWatchService.EventBatch batch) {
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

        service.start(CompletableFuture.completedFuture(null));

        // Prepare for the test
        CountDownLatch notified = new CountDownLatch(1);
        postResumeLatchRef.set(notified);
        received.set(null);
        filesChangedCount.set(0);

        // Pause and allow registration to settle
        Thread.sleep(500);
        service.pause();

        // Generate multiple rapid file changes that collectively exceed debounce window while paused
        int fileCount = 4;
        Path[] createdFiles = new Path[fileCount];
        for (int i = 0; i < fileCount; i++) {
            createdFiles[i] = tempDir.resolve("debounce_" + i + ".txt");
            Files.writeString(createdFiles[i], "data-" + i);
            // small gap to create distinct events but still rapid
            Thread.sleep(100);
            // append to create an additional MODIFY event
            Files.writeString(createdFiles[i], "-more", StandardOpenOption.APPEND);
        }

        // Wait longer than debounce delay to simulate that debounce would have fired if not paused
        Thread.sleep(800);

        // Ensure no notification was delivered while paused
        boolean deliveredWhilePaused = notified.await(1200, TimeUnit.MILLISECONDS);
        assertFalse(
                deliveredWhilePaused,
                "No notification should be delivered while watcher is paused (debounce coalescing)");

        // Resume and expect a single aggregated batch containing all created files
        service.resume();

        boolean deliveredAfterResume = notified.await(5, TimeUnit.SECONDS);
        assertTrue(deliveredAfterResume, "Buffered events should be delivered after resume (debounce coalescing)");

        // Exactly one delivery after resume
        assertEquals(
                1,
                filesChangedCount.get(),
                "Exactly one onFilesChanged delivery should occur after resume (debounce coalescing)");

        IWatchService.EventBatch batch = received.get();
        assertNotNull(batch, "EventBatch should be non-null after resume (debounce coalescing)");
        assertFalse(batch.files.isEmpty(), "EventBatch should contain files after resume (debounce coalescing)");
        assertEquals(fileCount, batch.files.size(), "EventBatch should contain all created files");

        for (Path p : createdFiles) {
            ProjectFile expected = new ProjectFile(tempDir, tempDir.relativize(p));
            assertTrue(batch.files.contains(expected), "EventBatch should contain file: " + p);
        }
    }
}
