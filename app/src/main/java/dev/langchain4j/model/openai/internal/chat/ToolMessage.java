package dev.langchain4j.model.openai.internal.chat;

import static dev.langchain4j.model.openai.internal.chat.Role.TOOL;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.util.Objects;

@JsonDeserialize(builder = ToolMessage.Builder.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public final class ToolMessage implements Message {

    @JsonProperty
    private final Role role = TOOL;

    @JsonProperty
    private final String toolCallId;

    @JsonProperty
    private final String content;

    @JsonProperty
    private final String name;

    public ToolMessage(Builder builder) {
        this.toolCallId = builder.toolCallId;
        this.content = builder.content;
        this.name = builder.name;
    }

    public Role role() {
        return role;
    }

    public String toolCallId() {
        return toolCallId;
    }

    public String content() {
        return content;
    }

    public String name() {
        return name;
    }

    public static ToolMessage from(String toolCallId, String content) {
        return from(toolCallId, content, null);
    }

    @Override
    public boolean equals(Object another) {
        if (this == another) return true;
        return another instanceof ToolMessage && equalTo((ToolMessage) another);
    }

    private boolean equalTo(ToolMessage another) {
        return Objects.equals(role, another.role)
                && Objects.equals(toolCallId, another.toolCallId)
                && Objects.equals(content, another.content)
                && Objects.equals(name, another.name);
    }

    @Override
    public int hashCode() {
        int h = 5381;
        h += (h << 5) + Objects.hashCode(role);
        h += (h << 5) + Objects.hashCode(toolCallId);
        h += (h << 5) + Objects.hashCode(content);
        h += (h << 5) + Objects.hashCode(name);
        return h;
    }

    @Override
    public String toString() {
        return "ToolMessage{"
                + "role=" + role
                + ", toolCallId=" + toolCallId
                + ", content=" + content
                + ", name=" + name
                + "}";
    }

    public static ToolMessage from(String toolCallId, String content, String name) {
        return ToolMessage.builder()
                .toolCallId(toolCallId)
                .content(content)
                .name(name)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static final class Builder {

        private String toolCallId;
        private String content;
        private String name;

        public Builder toolCallId(String toolCallId) {
            this.toolCallId = toolCallId;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public ToolMessage build() {
            return new ToolMessage(this);
        }
    }
}
