package ai.brokk.executor.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

public final class JobResponseSchemaSupport {
    private JobResponseSchemaSupport() {}

    public static Optional<String> validate(@Nullable JobSpec.ResponseSchema responseSchema) {
        if (responseSchema == null) {
            return Optional.empty();
        }
        if (responseSchema.name().isBlank()) {
            return Optional.of("responseSchema.name is required");
        }
        try {
            var root = responseSchema.schema();
            if (!root.isObject()) {
                return Optional.of("responseSchema.schema must be a JSON object");
            }
            if (!"object".equals(typeOf(root))) {
                return Optional.of("responseSchema.schema root type must be object");
            }
            toElement(root, "responseSchema.schema");
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of(
                    Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    public static ResponseFormat toResponseFormat(JobSpec.ResponseSchema responseSchema) {
        var schema = JsonSchema.builder()
                .name(responseSchema.name().strip())
                .rootElement(toElement(responseSchema.schema(), "responseSchema.schema"))
                .build();
        return ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(schema)
                .build();
    }

    private static JsonSchemaElement toElement(JsonNode node, String path) {
        if (!node.isObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        var type = typeOf(node);
        return switch (type) {
            case "object" -> objectSchema(node, path);
            case "array" -> arraySchema(node, path);
            case "string" -> stringSchema(node, path);
            case "integer" ->
                JsonIntegerSchema.builder().description(description(node, path)).build();
            case "number" ->
                JsonNumberSchema.builder().description(description(node, path)).build();
            case "boolean" ->
                JsonBooleanSchema.builder().description(description(node, path)).build();
            default -> throw new IllegalArgumentException(path + ".type is unsupported: " + type);
        };
    }

    private static JsonObjectSchema objectSchema(JsonNode node, String path) {
        var builder = JsonObjectSchema.builder().description(description(node, path));

        var propertiesNode = node.get("properties");
        if (propertiesNode != null) {
            if (!propertiesNode.isObject()) {
                throw new IllegalArgumentException(path + ".properties must be an object");
            }
            propertiesNode
                    .properties()
                    .forEach(entry -> builder.addProperty(
                            entry.getKey(), toElement(entry.getValue(), path + ".properties." + entry.getKey())));
        }

        var required = required(node, path);
        builder.required(required);

        if (propertiesNode != null) {
            var propertyNames = new HashSet<String>();
            propertiesNode.propertyStream().map(Map.Entry::getKey).forEach(propertyNames::add);
            var unknownRequired = required.stream()
                    .filter(name -> !propertyNames.contains(name))
                    .toList();
            if (!unknownRequired.isEmpty()) {
                throw new IllegalArgumentException(path + ".required contains unknown properties: " + unknownRequired);
            }
        }

        var additionalProperties = node.get("additionalProperties");
        if (additionalProperties != null) {
            if (!additionalProperties.isBoolean()) {
                throw new IllegalArgumentException(path + ".additionalProperties must be a boolean");
            }
            if (additionalProperties.booleanValue()) {
                throw new IllegalArgumentException(
                        path + ".additionalProperties=true is not supported for strict schemas");
            }
            builder.additionalProperties(false);
        } else {
            builder.additionalProperties(false);
        }

        return builder.build();
    }

    private static JsonArraySchema arraySchema(JsonNode node, String path) {
        var items = node.get("items");
        if (items == null) {
            throw new IllegalArgumentException(path + ".items is required for array schemas");
        }
        return JsonArraySchema.builder()
                .description(description(node, path))
                .items(toElement(items, path + ".items"))
                .build();
    }

    private static JsonSchemaElement stringSchema(JsonNode node, String path) {
        var enumNode = node.get("enum");
        if (enumNode == null) {
            return JsonStringSchema.builder()
                    .description(description(node, path))
                    .build();
        }
        if (!enumNode.isArray() || enumNode.isEmpty()) {
            throw new IllegalArgumentException(path + ".enum must be a non-empty array");
        }
        var values = new ArrayList<String>();
        for (var value : enumNode) {
            if (!value.isTextual()) {
                throw new IllegalArgumentException(path + ".enum must contain only strings");
            }
            values.add(value.textValue());
        }
        return JsonEnumSchema.builder()
                .description(description(node, path))
                .enumValues(values)
                .build();
    }

    private static List<String> required(JsonNode node, String path) {
        var requiredNode = node.get("required");
        if (requiredNode == null) {
            return List.of();
        }
        if (!requiredNode.isArray()) {
            throw new IllegalArgumentException(path + ".required must be an array");
        }
        var result = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (var value : requiredNode) {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalArgumentException(path + ".required must contain nonblank strings");
            }
            if (!seen.add(value.textValue())) {
                throw new IllegalArgumentException(
                        path + ".required contains duplicate property: " + value.textValue());
            }
            result.add(value.textValue());
        }
        return List.copyOf(result);
    }

    private static String typeOf(JsonNode node) {
        var typeNode = node.get("type");
        if (typeNode == null || !typeNode.isTextual() || typeNode.textValue().isBlank()) {
            throw new IllegalArgumentException("schema type is required");
        }
        return typeNode.textValue();
    }

    private static @Nullable String description(JsonNode node, String path) {
        var description = node.get("description");
        if (description == null) {
            return null;
        }
        if (!description.isTextual()) {
            throw new IllegalArgumentException(path + ".description must be a string");
        }
        return description.textValue();
    }
}
