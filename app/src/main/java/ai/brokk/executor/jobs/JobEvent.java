package ai.brokk.executor.jobs;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a single event in a job's event stream.
 * Events are immutable once created and stored with a monotonically increasing sequence number.
 */
public record JobEvent(
        @JsonProperty("seq") long seq,
        @JsonProperty("timestamp") long timestamp,
        @JsonProperty("type") String type,
        @JsonProperty("data") @Nullable Object data) {

    /**
     * Creates a new event without a sequence number (assigned later by JobStore).
     */
    public static JobEvent of(String type, @Nullable Object data) {
        return new JobEvent(-1, System.currentTimeMillis(), type, data);
    }

    /**
     * Creates a new event with a sequence number.
     */
    public static JobEvent withSeq(long seq, String type, @Nullable Object data) {
        return new JobEvent(seq, System.currentTimeMillis(), type, data);
    }
}
