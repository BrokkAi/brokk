package dev.langchain4j.model.openai.internal.chat;

import static dev.langchain4j.model.openai.internal.chat.Role.SYSTEM;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.util.Objects;

@JsonDeserialize(builder = SystemMessage.Builder.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public final class SystemMessage implements Message {

    @JsonProperty
    private final Role role = SYSTEM;

    @JsonProperty
    private final String content;

    @JsonProperty
    private final String name;

    @JsonProperty
    private final CacheControl cacheControl;

    public SystemMessage(Builder builder) {
        this.content = builder.content;
        this.name = builder.name;
        this.cacheControl = builder.cacheControl;
    }

    public Role role() {
        return role;
    }

    public String content() {
        return content;
    }

    public String name() {
        return name;
    }

    public CacheControl cacheControl() {
        return cacheControl;
    }

    @Override
    public boolean equals(Object another) {
        if (this == another) return true;
        return another instanceof SystemMessage && equalTo((SystemMessage) another);
    }

    private boolean equalTo(SystemMessage another) {
        return Objects.equals(role, another.role)
                && Objects.equals(content, another.content)
                && Objects.equals(name, another.name)
                && Objects.equals(cacheControl, another.cacheControl);
    }

    @Override
    public int hashCode() {
        int h = 5381;
        h += (h << 5) + Objects.hashCode(role);
        h += (h << 5) + Objects.hashCode(content);
        h += (h << 5) + Objects.hashCode(name);
        h += (h << 5) + Objects.hashCode(cacheControl);
        return h;
    }

    @Override
    public String toString() {
        return "SystemMessage{" + "role=" + role + ", content=" + content + ", name=" + name + ", cacheControl="
                + cacheControl + "}";
    }

    public static SystemMessage from(String content) {
        return SystemMessage.builder().content(content).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static final class Builder {

        private String content;
        private String name;
        private CacheControl cacheControl;

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder cacheControl(CacheControl cacheControl) {
            this.cacheControl = cacheControl;
            return this;
        }

        public SystemMessage build() {
            return new SystemMessage(this);
        }
    }
}
