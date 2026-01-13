package ai.brokk.gui.mop.webview;

import ai.brokk.gui.mop.ChunkMeta;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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
            @JsonSerialize(using = ToStringSerializer.class) ChatMessageType msgType,
            int epoch,
            boolean streaming,
            @JsonIgnore ChunkMeta chunkMeta)
            implements BrokkEvent {

        private record JsonMeta(boolean isNewMessage, boolean isReasoning, boolean isTerminal) {}

        @JsonProperty("meta")
        public JsonMeta meta() {
            return new JsonMeta(chunkMeta.isNewMessage(), chunkMeta.isReasoning(), chunkMeta.isTerminal());
        }

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

    record StaticDocument(int epoch, @Nullable String markdown) implements BrokkEvent {
        @Override
        public String getType() {
            return "static-document";
        }

        @Override
        public Integer getEpoch() {
            return epoch;
        }
    }
}
