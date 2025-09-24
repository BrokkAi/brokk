package dev.langchain4j.agent.tool;

import dev.langchain4j.model.chat.request.ToolChoice;
import java.util.List;

/** Encapsulates the tool specifications, the tool choice policy, and the object that owns the tools. */
public record ToolContext(List<ToolSpecification> toolSpecifications, ToolChoice toolChoice, Object toolOwner) {

    public static ToolContext empty() {
        return new ToolContext(List.of(), ToolChoice.AUTO, new Object());
    }
}
