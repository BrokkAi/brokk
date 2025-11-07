package ai.brokk.executor;

import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

/**
 * Encapsulates single-job reservation using an AtomicReference and CAS semantics.
 * This helper enables concurrency-focused unit testing without spinning up the HTTP server.
 */
public final class JobReservation {
    private final AtomicReference<@Nullable String> currentJobId = new AtomicReference<>();

    /**
     * Try to reserve the exclusive job slot for the given jobId.
     * Uses CAS to ensure only one concurrent job is executing.
     *
     * @param jobId the job identifier attempting to reserve the slot
     * @return true if reservation succeeded; false if another job holds the slot
     */
    public boolean tryReserve(String jobId) {
        assert !jobId.isBlank();
        return currentJobId.compareAndSet(null, jobId);
    }

    /**
     * Release the reservation only if the caller still owns it.
     *
     * @param jobId the job identifier attempting to release
     * @return true if released; false if not the owner or already released
     */
    public boolean releaseIfOwner(String jobId) {
        assert !jobId.isBlank();
        return currentJobId.compareAndSet(jobId, null);
    }

    /**
     * Returns the currently reserved job id, or null if none.
     */
    public @Nullable String current() {
        return currentJobId.get();
    }
}
