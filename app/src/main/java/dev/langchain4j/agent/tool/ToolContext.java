package dev.langchain4j.agent.tool;

import dev.langchain4j.model.chat.request.ToolChoice;
import io.github.jbellis.brokk.tools.ToolRegistry;
import java.util.List;

/**
 * Encapsulates the tool specifications, the tool choice policy, the object that owns the tools (legacy),
 * and optionally the ToolRegistry that produced these specifications.
 */
public record ToolContext(
        List<ToolSpecification> toolSpecifications, ToolChoice toolChoice, ToolRegistry toolRegistry) {

    public ToolContext {
        assert toolRegistry != null;
    }

    public static ToolContext empty() {
        return new ToolContext(List.of(), ToolChoice.AUTO, ToolRegistry.empty());
    }
}
