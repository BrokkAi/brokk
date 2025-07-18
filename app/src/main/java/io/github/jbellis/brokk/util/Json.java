package io.github.jbellis.brokk.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.nio.file.Path;

/**
 * JSON utility class for the Brokk project with proper Path serialization support.
 */
public class Json {
    
    private static final ObjectMapper MAPPER = createMapper();
    
    private Json() {
        // Utility class - no instantiation
    }
    
    /**
     * Creates and configures the ObjectMapper with Path support.
     */
    private static ObjectMapper createMapper() {
        var pathModule = new SimpleModule("PathModule")
                .addSerializer(Path.class, new PathSerializer())
                .addDeserializer(Path.class, new PathDeserializer());
        
        return new ObjectMapper()
                .registerModule(pathModule)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
    
    /**
     * Serializes an object to JSON string.
     */
    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize object to JSON", e);
        }
    }
    
    /**
     * Deserializes JSON string to an object of the specified type.
     */
    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize JSON to " + type.getSimpleName(), e);
        }
    }
    
    /**
     * Gets the configured ObjectMapper instance for advanced usage.
     */
    public static ObjectMapper getMapper() {
        return MAPPER;
    }
    
    /**
     * Serializes Path objects to their string representation.
     */
    private static class PathSerializer extends JsonSerializer<Path> {
        @Override
        public void serialize(Path value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(value.toString());
        }
    }
    
    /**
     * Deserializes string values back to normalized Path objects.
     */
    private static class PathDeserializer extends JsonDeserializer<Path> {
        @Override
        public Path deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            return Path.of(p.getValueAsString()).normalize();
        }
    }
}
