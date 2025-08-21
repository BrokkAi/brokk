package io.github.jbellis.brokk.gui.mop.webview;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import dev.langchain4j.data.message.ChatMessageType;

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
}
