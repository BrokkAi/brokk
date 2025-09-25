package io.github.jbellis.brokk.gui.mop.webview;

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

    /** Appends a task (either compressed summary or full messages) to the frontend's history. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record HistoryTask(
            int epoch, int taskSequence, boolean compressed, @Nullable String summary, @Nullable List<Message> messages)
            implements BrokkEvent {

        public static record Message(
                String text, @JsonSerialize(using = ToStringSerializer.class) ChatMessageType msgType) {}

        @Override
        public String getType() {
            return "history-task";
        }

        @Override
        public Integer getEpoch() {
            return epoch;
        }
    }
}
