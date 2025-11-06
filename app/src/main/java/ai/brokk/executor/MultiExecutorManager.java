package ai.brokk.executor;

import ai.brokk.executor.HeadlessExecutorService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Manages multiple HeadlessExecutorService instances keyed by workspace directory.
 *
 * Thread-safety:
 * - The {@code services} map is a concurrent map for fast concurrent reads.
 * - Mutating operations that create or remove services synchronize on the map instance
 *   to ensure only one creator/closer runs at a time.
 *
 * Lifecycle:
 * - open(Path) will return an existing Handle if the workspace is already opened,
 *   otherwise it will create a new HeadlessExecutorService and return a Handle for it.
 * - get(Path) returns the Handle if present, otherwise null.
 * - list() returns a snapshot list of Handles.
 * - close(Path) closes and removes the service for the given workspace.
 *
 * Note: open and close surface IO/close exceptions to callers so the caller can handle them.
 */
public final class MultiExecutorManager implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(MultiExecutorManager.class);

    private final ConcurrentMap<Path, Entry> services = new ConcurrentHashMap<>();

    public enum State {
        OPEN,
        CLOSED
    }

    /**
     * Public handle returned to callers that describes an executor instance.
     */
    public static record Handle(UUID execId, Path workspaceDir, State state) {}

    private static final class Entry {
        final UUID execId;
        final Path workspaceDir;
        final Path sessionsDir;
        volatile HeadlessExecutorService service;
        volatile State state;

        Entry(UUID execId, Path workspaceDir, Path sessionsDir, HeadlessExecutorService service) {
            this.execId = execId;
            this.workspaceDir = workspaceDir;
            this.sessionsDir = sessionsDir;
            this.service = service;
            this.state = State.OPEN;
        }

        Handle toHandle() {
            return new Handle(execId, workspaceDir, state);
        }
    }

    /**
     * Open (or return existing) an executor for the given workspace directory.
     *
     * Creates the sessions directory under workspace/.brokk/sessions if it does not exist.
     *
     * @param workspaceDir the workspace directory (will be normalized)
     * @return a Handle describing the executor instance
     * @throws IOException on I/O errors while creating directories or initializing the service
     */
    public Handle open(Path workspaceDir) throws IOException {
        Objects.requireNonNull(workspaceDir, "workspaceDir");
        var normalized = workspaceDir.toAbsolutePath().normalize();

        // Ensure sessions directory exists
        var sessionsDir = normalized.resolve(".brokk").resolve("sessions");
        Files.createDirectories(sessionsDir);

        synchronized (services) {
            var existing = services.get(normalized);
            if (existing != null && existing.state == State.OPEN && existing.service != null) {
                logger.debug("Returning existing executor for workspace {}", normalized);
                return existing.toHandle();
            }

            // Create new executor
            var execId = UUID.randomUUID();
            logger.info("Creating new HeadlessExecutorService: execId={}, workspace={}", execId, normalized);
            var svc = new HeadlessExecutorService(execId, normalized, sessionsDir);
            var entry = new Entry(execId, normalized, sessionsDir, svc);
            services.put(normalized, entry);
            return entry.toHandle();
        }
    }

    /**
     * Get the handle for an already-opened executor, or null if none exists.
     *
     * @param workspaceDir workspace directory (will be normalized)
     * @return Handle or null if not opened
     */
    public @Nullable Handle get(Path workspaceDir) {
        Objects.requireNonNull(workspaceDir, "workspaceDir");
        var normalized = workspaceDir.toAbsolutePath().normalize();
        var entry = services.get(normalized);
        return entry != null ? entry.toHandle() : null;
    }

    /**
     * List all executor handles (snapshot).
     *
     * @return list of Handle
     */
    public List<Handle> list() {
        var result = new ArrayList<Handle>();
        for (var entry : services.values()) {
            result.add(entry.toHandle());
        }
        return result;
    }

    /**
     * Close and remove the executor associated with the given workspace directory.
     *
     * If no executor exists for the workspace this is a no-op.
     *
     * @param workspaceDir workspace directory (will be normalized)
     * @throws Exception if closing the underlying service fails
     */
    public void close(Path workspaceDir) throws Exception {
        Objects.requireNonNull(workspaceDir, "workspaceDir");
        var normalized = workspaceDir.toAbsolutePath().normalize();

        Entry entry;
        synchronized (services) {
            entry = services.remove(normalized);
            if (entry == null) {
                logger.debug("No executor to close for workspace {}", normalized);
                return;
            }
            // Mark closed before attempting close to avoid racing opens (caller must handle expected exceptions)
            entry.state = State.CLOSED;
        }

        // Close outside synchronized block to avoid blocking other operations while closing.
        try {
            if (entry.service != null) {
                logger.info("Closing HeadlessExecutorService execId={}, workspace={}", entry.execId, normalized);
                entry.service.close();
            }
        } catch (Exception e) {
            logger.warn("Error while closing executor execId={}, workspace={}", entry.execId, normalized, e);
            throw e;
        }
    }

    /**
     * Close all managed executors and clear the manager.
     *
     * @throws Exception if any close operation fails; the first exception is rethrown after attempts to close all.
     */
    @Override
    public void close() throws Exception {
        List<Entry> entries;
        synchronized (services) {
            entries = new ArrayList<>(services.values());
            services.clear();
        }

        Exception firstEx = null;
        for (var entry : entries) {
            try {
                if (entry.service != null) {
                    logger.info("Closing HeadlessExecutorService execId={}, workspace={}", entry.execId, entry.workspaceDir);
                    entry.service.close();
                }
            } catch (Exception e) {
                logger.warn("Error closing executor execId={}, workspace={}", entry.execId, entry.workspaceDir, e);
                if (firstEx == null) {
                    firstEx = e;
                }
            } finally {
                entry.state = State.CLOSED;
            }
        }

        if (firstEx != null) {
            throw firstEx;
        }
    }
}
