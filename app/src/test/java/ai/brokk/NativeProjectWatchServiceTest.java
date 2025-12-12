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
    private Path gitRepoDir; // For worktree tests

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
        if (gitRepoDir != null) {
            try {
                // Best-effort cleanup
                Files.walk(gitRepoDir)
                        .map(Path::toFile)
                        .sorted((a, b) -> -a.compareTo(b))
                        .forEach(f -> f.delete());
            } catch (IOException ignored) {
            } finally {
                gitRepoDir = null;
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

    /**
     * Tests that git metadata events from an external directory (worktree scenario) are properly
     * captured. In a worktree, the .git directory is located in the main repository, not under
     * the worktree's root directory.
     */
    @Test
    public void testWorktreeGitMetadataEventsFromExternalDirectory() throws Exception {
        // Create two separate directories to simulate worktree scenario:
        // - tempDir: the worktree root (project root)
        // - gitRepoDir: the main repository root where .git lives
        tempDir = Files.createTempDirectory("native-watcher-worktree");
        gitRepoDir = Files.createTempDirectory("native-watcher-main-repo");

        // Create .git directory structure in the main repo
        Path gitDir = gitRepoDir.resolve(".git");
        Files.createDirectories(gitDir);
        Path refsDir = gitDir.resolve("refs").resolve("heads");
        Files.createDirectories(refsDir);

        // Create the watcher with gitRepoRoot pointing to the external main repo
        service = new NativeProjectWatchService(tempDir, gitRepoDir, null, List.of());

        // Listener state
        AtomicReference<CountDownLatch> latchRef = new AtomicReference<>();
        AtomicReference<IWatchService.EventBatch> received = new AtomicReference<>();
        AtomicInteger filesChangedCount = new AtomicInteger(0);

        service.addListener(new IWatchService.Listener() {
            @Override
            public void onFilesChanged(IWatchService.EventBatch batch) {
                filesChangedCount.incrementAndGet();
                received.set(batch);
                CountDownLatch l = latchRef.get();
                if (l != null) {
                    l.countDown();
                }
            }

            @Override
            public void onNoFilesChangedDuringPollInterval() {}
        });

        service.start(CompletableFuture.completedFuture(null));

        // Allow watcher to settle
        Thread.sleep(500);

        // Prepare for the test
        CountDownLatch notified = new CountDownLatch(1);
        latchRef.set(notified);
        received.set(null);
        filesChangedCount.set(0);

        // Simulate a git operation by modifying a file in .git (e.g., HEAD update after commit)
        Path headFile = gitDir.resolve("HEAD");
        Files.writeString(headFile, "ref: refs/heads/main\n");

        // Wait for the event to be delivered
        boolean delivered = notified.await(5, TimeUnit.SECONDS);
        assertTrue(delivered, "Git metadata event from external directory should be delivered");

        IWatchService.EventBatch batch = received.get();
        assertNotNull(batch, "EventBatch should be non-null");
        assertFalse(batch.files.isEmpty(), "EventBatch should contain files");

        // The file should be relativized to gitRepoRoot with .git prefix
        ProjectFile expectedFile = new ProjectFile(gitRepoDir, gitRepoDir.relativize(headFile));
        assertTrue(
                batch.files.contains(expectedFile),
                "EventBatch should contain the HEAD file with correct base: expected " + expectedFile + " but got "
                        + batch.files);

        // Verify the relative path starts with .git
        var gitFiles = batch.files.stream()
                .filter(pf -> pf.getRelPath().startsWith(".git"))
                .toList();
        assertFalse(gitFiles.isEmpty(), "EventBatch should contain files with .git prefix");
    }

    /**
     * Tests that regular file events in the worktree root are still captured correctly
     * even when gitRepoRoot is external.
     */
    @Test
    public void testWorktreeRegularFilesStillWork() throws Exception {
        // Create two separate directories to simulate worktree scenario
        tempDir = Files.createTempDirectory("native-watcher-worktree-regular");
        gitRepoDir = Files.createTempDirectory("native-watcher-main-repo-regular");

        // Create .git directory structure in the main repo
        Path gitDir = gitRepoDir.resolve(".git");
        Files.createDirectories(gitDir);

        // Create the watcher with gitRepoRoot pointing to the external main repo
        service = new NativeProjectWatchService(tempDir, gitRepoDir, null, List.of());

        // Listener state
        AtomicReference<CountDownLatch> latchRef = new AtomicReference<>();
        AtomicReference<IWatchService.EventBatch> received = new AtomicReference<>();

        service.addListener(new IWatchService.Listener() {
            @Override
            public void onFilesChanged(IWatchService.EventBatch batch) {
                received.set(batch);
                CountDownLatch l = latchRef.get();
                if (l != null) {
                    l.countDown();
                }
            }

            @Override
            public void onNoFilesChangedDuringPollInterval() {}
        });

        service.start(CompletableFuture.completedFuture(null));

        // Allow watcher to settle
        Thread.sleep(500);

        // Prepare for the test
        CountDownLatch notified = new CountDownLatch(1);
        latchRef.set(notified);
        received.set(null);

        // Create a regular file in the worktree root
        Path sourceFile = tempDir.resolve("Source.java");
        Files.writeString(sourceFile, "public class Source {}");

        // Wait for the event to be delivered
        boolean delivered = notified.await(5, TimeUnit.SECONDS);
        assertTrue(delivered, "Regular file event in worktree root should be delivered");

        IWatchService.EventBatch batch = received.get();
        assertNotNull(batch, "EventBatch should be non-null");

        // The file should be relativized to tempDir (worktree root)
        ProjectFile expectedFile = new ProjectFile(tempDir, tempDir.relativize(sourceFile));
        assertTrue(
                batch.files.contains(expectedFile),
                "EventBatch should contain the source file with worktree root as base");
    }

    /**
     * Tests that the service correctly resolves and watches git metadata when .git is a FILE
     * (the real worktree case where .git contains "gitdir: /path/to/main/.git/worktrees/xxx").
     */
    @Test
    public void testWorktreeWithGitFile() throws Exception {
        // Create worktree directory (project root)
        tempDir = Files.createTempDirectory("native-watcher-worktree-gitfile");

        // Create external git metadata directory (simulates main repo's .git/worktrees/xxx)
        gitRepoDir = Files.createTempDirectory("native-watcher-external-git");
        Path externalGitDir = gitRepoDir.resolve(".git").resolve("worktrees").resolve("myworktree");
        Files.createDirectories(externalGitDir);
        Path refsDir = externalGitDir.resolve("refs").resolve("heads");
        Files.createDirectories(refsDir);

        // Create .git FILE in worktree pointing to external directory
        Path gitFile = tempDir.resolve(".git");
        Files.writeString(gitFile, "gitdir: " + externalGitDir);

        // Create watcher with worktree as BOTH root and gitRepoRoot (real scenario)
        service = new NativeProjectWatchService(tempDir, tempDir, null, List.of());

        // Listener state
        AtomicReference<CountDownLatch> latchRef = new AtomicReference<>();
        AtomicReference<IWatchService.EventBatch> received = new AtomicReference<>();

        service.addListener(new IWatchService.Listener() {
            @Override
            public void onFilesChanged(IWatchService.EventBatch batch) {
                received.set(batch);
                CountDownLatch l = latchRef.get();
                if (l != null) {
                    l.countDown();
                }
            }

            @Override
            public void onNoFilesChangedDuringPollInterval() {}
        });

        service.start(CompletableFuture.completedFuture(null));

        // Allow watcher to settle
        Thread.sleep(500);

        // Prepare for the test
        CountDownLatch notified = new CountDownLatch(1);
        latchRef.set(notified);
        received.set(null);

        // Simulate a git operation by modifying HEAD in the external git directory
        Path headFile = externalGitDir.resolve("HEAD");
        Files.writeString(headFile, "ref: refs/heads/main\n");

        // Wait for the event to be delivered
        boolean delivered = notified.await(5, TimeUnit.SECONDS);
        assertTrue(delivered, "Git metadata event from external worktree directory should be delivered");

        IWatchService.EventBatch batch = received.get();
        assertNotNull(batch, "EventBatch should be non-null");
        assertFalse(batch.files.isEmpty(), "EventBatch should contain files");

        // Verify the event was received (the path handling is tested separately)
        assertTrue(
                batch.files.stream().anyMatch(pf -> pf.getRelPath().toString().contains("HEAD")),
                "EventBatch should contain the HEAD file change");
    }
}
