package ai.brokk.executor.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Represents a single event in a job's event log.
 *
 * @param seq Monotonically increasing sequence number for ordering.
 * @param ts Unix timestamp (milliseconds) when the event was recorded.
 * @param eventType The type of event (e.g., "LLM_TOKEN", "ERROR", "NOTIFICATION").
 * @param payload The event payload; can be any JSON-serializable object or map.
 */
public record JobEvent(long seq, long ts, @JsonProperty("type") String eventType, Map<String, Object> payload) {

    /**
     * Validate that seq >= 0, ts >= 0, and eventType is non-blank.
     */
    public JobEvent {
        if (seq < 0) {
            throw new IllegalArgumentException("seq must be non-negative, got: " + seq);
        }
        if (ts < 0) {
            throw new IllegalArgumentException("ts must be non-negative, got: " + ts);
        }
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
    }

    /**
     * Factory method to create an event with current timestamp.
     * @param seq The sequence number.
     * @param eventType The event type.
     * @param payload The event payload.
     * @return A new JobEvent with ts set to current time.
     */
    public static JobEvent of(long seq, String eventType, Map<String, Object> payload) {
        return new JobEvent(seq, System.currentTimeMillis(), eventType, payload);
    }

    /**
     * Factory method to create an event with a simple key-value payload.
     * @param seq The sequence number.
     * @param eventType The event type.
     * @param key The payload key.
     * @param value The payload value.
     * @return A new JobEvent with a single-entry payload map.
     */
    public static JobEvent of(long seq, String eventType, String key, Object value) {
        return of(seq, eventType, Map.of(key, value));
    }
}
