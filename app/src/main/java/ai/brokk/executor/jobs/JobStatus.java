package ai.brokk.executor.jobs;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Represents the current status of a job.
 * This is persisted to status.json and updated atomically.
 */
public record JobStatus(
        @JsonProperty("jobId") String jobId,
        @JsonProperty("state") String state,
        @JsonProperty("startTime") long startTime,
        @JsonProperty("endTime") long endTime,
        @JsonProperty("progressPercent") int progressPercent,
        @JsonProperty("result") @Nullable Object result,
        @JsonProperty("error") @Nullable String error,
        @JsonProperty("metadata") Map<String, String> metadata) {

    public enum State {
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    /**
     * Creates an initial queued status for a new job.
     */
    public static JobStatus queued(String jobId) {
        return new JobStatus(jobId, State.QUEUED.name(), System.currentTimeMillis(), 0, 0, null, null, Map.of());
    }

    /**
     * Returns a copy of this status with updated state and progress.
     */
    public JobStatus withState(String state) {
        return new JobStatus(jobId, state, startTime, endTime, progressPercent, result, error, metadata);
    }

    /**
     * Returns a copy of this status with updated progress.
     */
    public JobStatus withProgress(int progressPercent) {
        return new JobStatus(jobId, state, startTime, endTime, progressPercent, result, error, metadata);
    }

    /**
     * Returns a copy of this status marked as completed.
     */
    public JobStatus completed(@Nullable Object result) {
        return new JobStatus(
                jobId, State.COMPLETED.name(), startTime, System.currentTimeMillis(), 100, result, null, metadata);
    }

    /**
     * Returns a copy of this status marked as failed.
     */
    public JobStatus failed(String errorMsg) {
        return new JobStatus(
                jobId,
                State.FAILED.name(),
                startTime,
                System.currentTimeMillis(),
                progressPercent,
                null,
                errorMsg,
                metadata);
    }

    /**
     * Returns a copy of this status marked as cancelled.
     */
    public JobStatus cancelled() {
        return new JobStatus(
                jobId,
                State.CANCELLED.name(),
                startTime,
                System.currentTimeMillis(),
                progressPercent,
                null,
                null,
                metadata);
    }

    /**
     * Returns a copy of this status with metadata added/updated.
     */
    public JobStatus withMetadata(String key, String value) {
        var newMetadata = new java.util.HashMap<>(metadata);
        newMetadata.put(key, value);
        return new JobStatus(jobId, state, startTime, endTime, progressPercent, result, error, newMetadata);
    }
}
