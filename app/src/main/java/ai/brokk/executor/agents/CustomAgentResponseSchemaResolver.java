package ai.brokk.executor.agents;

import ai.brokk.executor.jobs.JobSpec;
import ai.brokk.executor.jobs.ResponseSchemaRegistry;
import ai.brokk.tools.ToolRegistry;

final class CustomAgentResponseSchemaResolver {
    private CustomAgentResponseSchemaResolver() {}

    static JobSpec.ResponseSchema resolve(String responseSchemaName, ResponseSchemaRegistry registry) {
        var name = responseSchemaName.strip();
        if (name.isBlank()) {
            throw new ToolRegistry.ToolValidationException("responseSchemaName is required");
        }

        return registry.resolve(name)
                .orElseThrow(() -> new ToolRegistry.ToolValidationException(
                        "responseSchemaName '%s' was not found in the parent task schemas. Available schemas: %s"
                                .formatted(name, registry.names())));
    }
}
