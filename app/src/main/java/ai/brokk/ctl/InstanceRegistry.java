package ai.brokk.ctl;

import ai.brokk.util.AtomicWrites;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages on-disk registration of a running Brokk instance.
 *
 * Responsibilities:
 * - create `<instanceId>.json` in instances dir on start
 * - periodically update `lastSeenMs` (heartbeat)
 * - update `projects[]` on demand
 * - delete the record on stop()
 *
 * Uses AtomicWrites.atomicOverwrite for atomic file updates.
 */
public final class InstanceRegistry implements AutoCloseable {
    private final CtlConfigPaths paths;
    private final String instanceId;
    private final Integer pid; // nullable
    private final String listenAddr;
    private volatile List<String> projects;
    private final String brokkctlVersion;
    private final long startedAtMs;
    private final long heartbeatMs;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> heartbeatFuture;
    private final Path recordPath;

    public InstanceRegistry(CtlConfigPaths paths,
                            String instanceId,
                            Integer pid,
                            String listenAddr,
                            List<String> projects,
                            String brokkctlVersion,
                            long heartbeatMs) {
        this.paths = Objects.requireNonNull(paths);
        this.instanceId = Objects.requireNonNull(instanceId);
        this.pid = pid;
        this.listenAddr = Objects.requireNonNull(listenAddr);
        this.projects = projects;
        this.brokkctlVersion = Objects.requireNonNull(brokkctlVersion);
        this.startedAtMs = System.currentTimeMillis();
        if (heartbeatMs <= 0) throw new IllegalArgumentException("heartbeatMs must be > 0");
        this.heartbeatMs = heartbeatMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "InstanceRegistry-" + instanceId);
            t.setDaemon(true);
            return t;
        });
        this.recordPath = paths.getInstancesDir().resolve(instanceId + ".json");
    }

    /**
     * Start the registry: ensure instances directory exists, write initial record, and schedule heartbeats.
     */
    public synchronized void start() throws IOException {
        paths.ensureInstancesDirExists();
        writeRecord(); // initial write
        this.heartbeatFuture = scheduler.scheduleAtFixedRate(() -> {
            try {
                writeRecord();
            } catch (IOException e) {
                // Best-effort: don't let scheduled task die silently
                System.err.println("InstanceRegistry heartbeat failed: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Update the set of open projects for this instance and persist immediately.
     */
    public synchronized void updateProjects(List<String> projects) throws IOException {
        this.projects = projects;
        writeRecord();
    }

    /**
     * Write the record to disk atomically.
     */
    private synchronized void writeRecord() throws IOException {
        long now = System.currentTimeMillis();
        InstanceRecord rec = new InstanceRecord(instanceId, pid, listenAddr, projects, brokkctlVersion, startedAtMs, now);
        AtomicWrites.atomicOverwrite(recordPath, rec.toJson());
    }

    /**
     * Stop heartbeats and remove the on-disk record.
     */
    public synchronized void stop() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
        scheduler.shutdownNow();
        try {
            Files.deleteIfExists(recordPath);
        } catch (IOException e) {
            // Best-effort cleanup; don't throw from stop
            System.err.println("Failed to delete instance record " + recordPath + ": " + e.getMessage());
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Return the path to the record file for testing/inspection.
     */
    public Path getRecordPath() {
        return recordPath;
    }

    public String getInstanceId() {
        return instanceId;
    }
}
