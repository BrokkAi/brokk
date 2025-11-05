package ai.brokk.executor.model;

import org.jetbrains.annotations.Nullable;

/**
 * Represents the current status of a job execution.
 *
 * @param state The current state of the job.
 * @param createdAt Unix timestamp (milliseconds) when the job was created.
 * @param startedAt Unix timestamp when execution started; null if not yet started.
 * @param completedAt Unix timestamp when execution completed; null if still running.
 * @param attempts Number of execution attempts (retries + 1).
 * @param lastSeq The last event sequence number appended to the job's event log.
 */
public record JobStatus(
        State state, long createdAt, @Nullable Long startedAt, @Nullable Long completedAt, int attempts, long lastSeq) {

    /**
     * Job execution state.
     */
    public enum State {
        /** Job created but not yet started. */
        PENDING,
        /** Job is currently executing. */
        RUNNING,
        /** Job completed successfully. */
        SUCCEEDED,
        /** Job failed (permanent error). */
        FAILED,
        /** Job was cancelled by the user. */
        CANCELLED
    }

    /**
     * Validate that state is non-null, timestamps are non-negative, and attempts >= 1.
     */
    public JobStatus {
        if (createdAt < 0) {
            throw new IllegalArgumentException("createdAt must be non-negative, got: " + createdAt);
        }
        if (startedAt != null && startedAt < 0) {
            throw new IllegalArgumentException("startedAt must be non-negative, got: " + startedAt);
        }
        if (completedAt != null && completedAt < 0) {
            throw new IllegalArgumentException("completedAt must be non-negative, got: " + completedAt);
        }
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be >= 1, got: " + attempts);
        }
        if (lastSeq < -1) {
            throw new IllegalArgumentException("lastSeq must be >= -1, got: " + lastSeq);
        }
    }

    /**
     * Create a new PENDING job status at the current time.
     * @return A new JobStatus with state PENDING, createdAt now, and other fields set to defaults.
     */
    public static JobStatus pending() {
        return new JobStatus(State.PENDING, System.currentTimeMillis(), null, null, 1, -1);
    }

    /**
     * Transition to RUNNING state with current timestamp.
     * @return A new JobStatus with state RUNNING and startedAt set to now.
     */
    public JobStatus toRunning() {
        if (!state.equals(State.PENDING)) {
            throw new IllegalStateException(
                    "Cannot transition from " + state + " to RUNNING; only PENDING jobs can start");
        }
        return new JobStatus(State.RUNNING, createdAt, System.currentTimeMillis(), null, attempts, lastSeq);
    }

    /**
     * Transition to SUCCEEDED state with current timestamp.
     * @return A new JobStatus with state SUCCEEDED and completedAt set to now.
     */
    public JobStatus toSucceeded() {
        if (!state.equals(State.RUNNING)) {
            throw new IllegalStateException(
                    "Cannot transition from " + state + " to SUCCEEDED; only RUNNING jobs can succeed");
        }
        return new JobStatus(State.SUCCEEDED, createdAt, startedAt, System.currentTimeMillis(), attempts, lastSeq);
    }

    /**
     * Transition to FAILED state with current timestamp.
     * @return A new JobStatus with state FAILED and completedAt set to now.
     */
    public JobStatus toFailed() {
        if (state.equals(State.SUCCEEDED) || state.equals(State.CANCELLED)) {
            throw new IllegalStateException("Cannot transition from " + state + " to FAILED; terminal state");
        }
        return new JobStatus(State.FAILED, createdAt, startedAt, System.currentTimeMillis(), attempts, lastSeq);
    }

    /**
     * Transition to CANCELLED state with current timestamp.
     * @return A new JobStatus with state CANCELLED and completedAt set to now.
     */
    public JobStatus toCancelled() {
        if (state.equals(State.SUCCEEDED) || state.equals(State.FAILED)) {
            throw new IllegalStateException("Cannot transition from " + state + " to CANCELLED; already terminal");
        }
        return new JobStatus(State.CANCELLED, createdAt, startedAt, System.currentTimeMillis(), attempts, lastSeq);
    }

    /**
     * Increment the attempt count and reset to PENDING (for retry).
     * @return A new JobStatus with state PENDING, incremented attempts, and cleared timestamps.
     */
    public JobStatus retryReset() {
        return new JobStatus(State.PENDING, createdAt, null, null, attempts + 1, lastSeq);
    }

    /**
     * Update the last event sequence number.
     * @param newLastSeq The new sequence number.
     * @return A new JobStatus with the updated lastSeq.
     */
    public JobStatus withLastSeq(long newLastSeq) {
        if (newLastSeq < -1) {
            throw new IllegalArgumentException("lastSeq must be >= -1, got: " + newLastSeq);
        }
        return new JobStatus(state, createdAt, startedAt, completedAt, attempts, newLastSeq);
    }

    /**
     * Check if the job is in a terminal state.
     * @return true if the job is SUCCEEDED, FAILED, or CANCELLED.
     */
    public boolean isTerminal() {
        return state.equals(State.SUCCEEDED) || state.equals(State.FAILED) || state.equals(State.CANCELLED);
    }
}
