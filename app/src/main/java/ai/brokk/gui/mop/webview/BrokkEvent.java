package ai.brokk.gui.mop.webview;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import dev.langchain4j.data.message.ChatMessageType;
import java.util.List;
import org.jetbrains.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface BrokkEvent {
    String getType();

    Integer getEpoch();

    record Chunk(
            String text,
            boolean isNew,
            @JsonSerialize(using = ToStringSerializer.class) ChatMessageType msgType,
            int epoch,
            boolean streaming,
            boolean reasoning)
            implements BrokkEvent {
        @Override
        public String getType() {
            return "chunk";
        }

        @Override
        public Integer getEpoch() {
            return epoch;
        }
    }

    record Clear(int epoch) implements BrokkEvent {
        @Override
        public String getType() {
            return "clear";
        }

        @Override
        public Integer getEpoch() {
            return epoch;
        }
    }

    /** Clears the frontend's stored history */
    record HistoryReset(int epoch) implements BrokkEvent {
        @Override
        public String getType() {
            return "history-reset";
        }

        @Override
        public Integer getEpoch() {
            return epoch;
        }
    }

    /**
     * Appends a task to the frontend's history.
     * Can contain summary (when compressed=true), messages (when available), or both.
     * The compressed flag indicates whether the AI uses a summary for this task.
     * <b>Note:</b> At least one of {@code summary} or {@code messages} must be non-null.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record HistoryTask(
            int epoch, int taskSequence, boolean compressed, @Nullable String summary, @Nullable List<Message> messages)
            implements BrokkEvent {

        public static record Message(
                String text,
                @JsonSerialize(using = ToStringSerializer.class) ChatMessageType msgType,
                boolean reasoning) {}

        @Override
        public String getType() {
            return "history-task";
        }

        @Override
        public Integer getEpoch() {
            return epoch;
        }
    }

    /**
     * Delivers a compressed summary for the live thread.
     * Used when the AI produces a summary for the current live task.
     * The taskSequence identifies the backend TaskEntry; the frontend maps this to its own threadId.
     */
    record LiveSummary(int epoch, int taskSequence, boolean compressed, String summary) implements BrokkEvent {
        @Override
        public String getType() {
            return "live-summary";
        }

        @Override
        public Integer getEpoch() {
            return epoch;
        }
    }
}
