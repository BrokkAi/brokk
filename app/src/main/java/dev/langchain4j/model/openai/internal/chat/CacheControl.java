package dev.langchain4j.model.openai.internal.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.util.Objects;

@JsonDeserialize(builder = CacheControl.Builder.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CacheControl {

    @JsonProperty
    private final String type;

    public CacheControl(Builder builder) {
        this.type = builder.type;
    }

    public String type() {
        return type;
    }

    @Override
    public boolean equals(Object another) {
        if (this == another) return true;
        return another instanceof CacheControl && equalTo((CacheControl) another);
    }

    private boolean equalTo(CacheControl another) {
        return Objects.equals(type, another.type);
    }

    @Override
    public int hashCode() {
        int h = 5381;
        h += (h << 5) + Objects.hashCode(type);
        return h;
    }

    @Override
    public String toString() {
        return "CacheControl{" + "type=" + type + "}";
    }

    public static CacheControl from(String type) {
        return CacheControl.builder().type(type).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static final class Builder {

        private String type;

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public CacheControl build() {
            return new CacheControl(this);
        }
    }
}
