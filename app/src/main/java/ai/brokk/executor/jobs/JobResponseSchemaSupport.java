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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class JobResponseSchemaSupport {
    static final int MAX_NAME_LENGTH = 128;
    static final int MAX_STRING_LENGTH = 2048;
    static final int MAX_DEPTH = 12;
    static final int MAX_NODES = 256;
    static final int MAX_PROPERTIES = 128;
    static final int MAX_ENUM_VALUES = 128;
    private static final Set<String> OBJECT_KEYS =
            Set.of("type", "description", "properties", "required", "additionalProperties");
    private static final Set<String> ARRAY_KEYS = Set.of("type", "description", "items");
    private static final Set<String> STRING_KEYS = Set.of("type", "description", "enum");
    private static final Set<String> SCALAR_KEYS = Set.of("type", "description");

    private JobResponseSchemaSupport() {}

    public static Optional<String> validate(@Nullable JobSpec.ResponseSchema responseSchema) {
        if (responseSchema == null) {
            return Optional.empty();
        }
        if (responseSchema.name().isBlank()) {
            return Optional.of("responseSchema.name is required");
        }
        if (responseSchema.name().strip().length() > MAX_NAME_LENGTH) {
            return Optional.of("responseSchema.name is too long");
        }
        try {
            var root = responseSchema.schema();
            if (!root.isObject()) {
                return Optional.of("responseSchema.schema must be a JSON object");
            }
            if (!"object".equals(typeOf(root))) {
                return Optional.of("responseSchema.schema root type must be object");
            }
            toElement(root, "responseSchema.schema", new Limits());
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of(
                    Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    public static ResponseFormat toResponseFormat(JobSpec.ResponseSchema responseSchema) {
        var schema = JsonSchema.builder()
                .name(responseSchema.name().strip())
                .rootElement(toElement(responseSchema.schema(), "responseSchema.schema", new Limits()))
                .build();
        return ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(schema)
                .build();
    }

    private static JsonSchemaElement toElement(JsonNode node, String path, Limits limits) {
        if (!node.isObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        limits.enterNode(path);
        var type = typeOf(node);
        return switch (type) {
            case "object" -> objectSchema(node, path, limits);
            case "array" -> arraySchema(node, path, limits);
            case "string" -> stringSchema(node, path);
            case "integer" ->
                scalarSchema(
                        node,
                        path,
                        JsonIntegerSchema.builder()
                                .description(description(node, path))
                                .build());
            case "number" ->
                scalarSchema(
                        node,
                        path,
                        JsonNumberSchema.builder()
                                .description(description(node, path))
                                .build());
            case "boolean" ->
                scalarSchema(
                        node,
                        path,
                        JsonBooleanSchema.builder()
                                .description(description(node, path))
                                .build());
            default -> throw new IllegalArgumentException(path + ".type is unsupported: " + type);
        };
    }

    private static JsonSchemaElement scalarSchema(JsonNode node, String path, JsonSchemaElement schema) {
        ensureSupportedKeys(node, path, SCALAR_KEYS);
        return schema;
    }

    private static JsonObjectSchema objectSchema(JsonNode node, String path, Limits limits) {
        ensureSupportedKeys(node, path, OBJECT_KEYS);
        var builder = JsonObjectSchema.builder().description(description(node, path));

        var propertiesNode = node.get("properties");
        var propertyNames = new HashSet<String>();
        if (propertiesNode != null) {
            if (!propertiesNode.isObject()) {
                throw new IllegalArgumentException(path + ".properties must be an object");
            }
            if (propertiesNode.size() > MAX_PROPERTIES) {
                throw new IllegalArgumentException(path + ".properties has too many properties");
            }
            propertiesNode.properties().forEach(entry -> {
                validateString(entry.getKey(), path + ".properties property name", MAX_NAME_LENGTH);
                if (!propertyNames.add(entry.getKey())) {
                    throw new IllegalArgumentException(
                            path + ".properties contains duplicate property: " + entry.getKey());
                }
                builder.addProperty(
                        entry.getKey(),
                        toElement(entry.getValue(), path + ".properties." + entry.getKey(), limits.child()));
            });
        }

        var required = required(node, path);
        builder.required(required);

        if (propertiesNode != null) {
            var unknownRequired = required.stream()
                    .filter(name -> !propertyNames.contains(name))
                    .toList();
            if (!unknownRequired.isEmpty()) {
                throw new IllegalArgumentException(path + ".required contains unknown properties: " + unknownRequired);
            }
            var missingRequired = propertyNames.stream()
                    .filter(name -> !required.contains(name))
                    .toList();
            if (!missingRequired.isEmpty()) {
                throw new IllegalArgumentException(
                        path + ".required must include every property for strict schemas: " + missingRequired);
            }
        } else if (!required.isEmpty()) {
            throw new IllegalArgumentException(path + ".required cannot be used without properties");
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

    private static JsonArraySchema arraySchema(JsonNode node, String path, Limits limits) {
        ensureSupportedKeys(node, path, ARRAY_KEYS);
        var items = node.get("items");
        if (items == null) {
            throw new IllegalArgumentException(path + ".items is required for array schemas");
        }
        return JsonArraySchema.builder()
                .description(description(node, path))
                .items(toElement(items, path + ".items", limits.child()))
                .build();
    }

    private static JsonSchemaElement stringSchema(JsonNode node, String path) {
        ensureSupportedKeys(node, path, STRING_KEYS);
        var enumNode = node.get("enum");
        if (enumNode == null) {
            return JsonStringSchema.builder()
                    .description(description(node, path))
                    .build();
        }
        if (!enumNode.isArray() || enumNode.isEmpty()) {
            throw new IllegalArgumentException(path + ".enum must be a non-empty array");
        }
        if (enumNode.size() > MAX_ENUM_VALUES) {
            throw new IllegalArgumentException(path + ".enum has too many values");
        }
        var values = new ArrayList<String>();
        for (var value : enumNode) {
            if (!value.isTextual()) {
                throw new IllegalArgumentException(path + ".enum must contain only strings");
            }
            validateString(value.textValue(), path + ".enum value", MAX_STRING_LENGTH);
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
        if (requiredNode.size() > MAX_PROPERTIES) {
            throw new IllegalArgumentException(path + ".required has too many entries");
        }
        var result = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (var value : requiredNode) {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalArgumentException(path + ".required must contain nonblank strings");
            }
            validateString(value.textValue(), path + ".required value", MAX_NAME_LENGTH);
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
        validateString(typeNode.textValue(), "schema type", MAX_NAME_LENGTH);
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
        validateString(description.textValue(), path + ".description", MAX_STRING_LENGTH);
        return description.textValue();
    }

    private static void ensureSupportedKeys(JsonNode node, String path, Set<String> supported) {
        node.properties().forEach(entry -> {
            if (!supported.contains(entry.getKey())) {
                throw new IllegalArgumentException(path + "." + entry.getKey() + " is not supported");
            }
        });
    }

    private static void validateString(String value, String path, int maxLength) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(path + " is too long");
        }
    }

    private static final class Limits {
        private final int depth;
        private final int[] nodes;

        Limits() {
            this(0, new int[1]);
        }

        private Limits(int depth, int[] nodes) {
            this.depth = depth;
            this.nodes = nodes;
        }

        void enterNode(String path) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException(path + " exceeds maximum schema depth");
            }
            nodes[0]++;
            if (nodes[0] > MAX_NODES) {
                throw new IllegalArgumentException("responseSchema.schema has too many schema nodes");
            }
        }

        Limits child() {
            return new Limits(depth + 1, nodes);
        }
    }
}
