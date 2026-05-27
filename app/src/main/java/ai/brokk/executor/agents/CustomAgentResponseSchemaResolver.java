package ai.brokk.executor.agents;

import ai.brokk.executor.jobs.JobResponseSchemaSupport;
import ai.brokk.executor.jobs.JobSpec;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class CustomAgentResponseSchemaResolver {
    private CustomAgentResponseSchemaResolver() {}

    static JobSpec.ResponseSchema resolve(Object rawSchemaArgument, @Nullable String schemaSource) {
        var argument = parseArgument(rawSchemaArgument);
        if (argument.schema() != null && !isEmptyObject(argument.schema())) {
            return validate(new JobSpec.ResponseSchema(argument.name(), argument.schema()));
        }

        var schemas = schemasByName(schemaSource);
        var name = argument.name().strip();
        if (name.isBlank()) {
            if (schemas.size() == 1) {
                return schemas.values().iterator().next();
            }
            throw new ToolRegistry.ToolValidationException("responseSchema.name is required");
        }

        var resolved = schemas.get(name);
        if (resolved == null) {
            throw new ToolRegistry.ToolValidationException(
                    "responseSchema.schema is missing or empty for '%s'; provide a complete responseSchema or reference a schema name present in the parent task"
                            .formatted(name));
        }
        return resolved;
    }

    private static SchemaArgument parseArgument(Object rawSchemaArgument) {
        if (rawSchemaArgument instanceof JobSpec.ResponseSchema responseSchema) {
            return new SchemaArgument(responseSchema.name(), responseSchema.schema());
        }
        if (rawSchemaArgument instanceof String name) {
            return new SchemaArgument(name, null);
        }

        JsonNode node = Json.getMapper().valueToTree(rawSchemaArgument);
        if (node.isTextual()) {
            return new SchemaArgument(node.asText(), null);
        }
        if (!node.isObject()) {
            throw new ToolRegistry.ToolValidationException("responseSchema must be a schema name or object");
        }
        if (isEmptyObject(node)) {
            return new SchemaArgument("", null);
        }

        var nameNode = node.get("name");
        if (nameNode == null || !nameNode.isTextual()) {
            throw new ToolRegistry.ToolValidationException("responseSchema.name is required");
        }
        return new SchemaArgument(nameNode.asText(), node.get("schema"));
    }

    private static Map<String, JobSpec.ResponseSchema> schemasByName(@Nullable String schemaSource) {
        if (schemaSource == null || schemaSource.isBlank()) {
            return Map.of();
        }

        var schemas = new HashMap<String, JobSpec.ResponseSchema>();
        for (int i = 0; i < schemaSource.length(); i++) {
            if (schemaSource.charAt(i) != '{') {
                continue;
            }
            int end = findMatchingBrace(schemaSource, i);
            if (end < 0) {
                continue;
            }
            collectSchema(schemaSource.substring(i, end + 1), schemas);
        }
        return schemas;
    }

    private static void collectSchema(String candidateJson, Map<String, JobSpec.ResponseSchema> schemas) {
        try {
            var node = Json.getMapper().readTree(candidateJson);
            collectSchema(node, schemas);
        } catch (Exception ignored) {
            // The parent prompt is free-form text. Most brace-delimited snippets are not response schemas.
        }
    }

    private static void collectSchema(JsonNode node, Map<String, JobSpec.ResponseSchema> schemas) {
        if (!node.isObject()) {
            return;
        }

        var nameNode = node.get("name");
        var schemaNode = node.get("schema");
        if (nameNode != null && nameNode.isTextual() && schemaNode != null) {
            var schema = new JobSpec.ResponseSchema(nameNode.asText(), schemaNode);
            if (JobResponseSchemaSupport.validate(schema).isEmpty()) {
                schemas.putIfAbsent(schema.name().strip(), schema);
            }
        }

        node.properties().forEach(entry -> collectSchema(entry.getValue(), schemas));
    }

    private static int findMatchingBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isEmptyObject(JsonNode node) {
        return node.isObject() && !node.fieldNames().hasNext();
    }

    private static JobSpec.ResponseSchema validate(JobSpec.ResponseSchema responseSchema) {
        var error = JobResponseSchemaSupport.validate(responseSchema);
        if (error.isPresent()) {
            throw new ToolRegistry.ToolValidationException(error.get());
        }
        return responseSchema;
    }

    private record SchemaArgument(String name, @Nullable JsonNode schema) {}
}
