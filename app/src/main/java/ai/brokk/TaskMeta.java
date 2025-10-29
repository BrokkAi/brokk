package ai.brokk;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record TaskMeta(TaskType type, ModelSpec primaryModel) {

    @JsonCreator
    public TaskMeta(@JsonProperty("type") TaskType type, @JsonProperty("primaryModel") ModelSpec primaryModel) {
        this.type = requireNonNull(type, "type");
        this.primaryModel = requireNonNull(primaryModel, "primaryModel");
    }
}
