package ai.brokk.executor.jobs;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ResponseSchemaRegistry {
    private static final ResponseSchemaRegistry EMPTY = new ResponseSchemaRegistry(Map.of());

    private final Map<String, JobSpec.ResponseSchema> schemasByName;

    private ResponseSchemaRegistry(Map<String, JobSpec.ResponseSchema> schemasByName) {
        this.schemasByName = schemasByName;
    }

    public static ResponseSchemaRegistry empty() {
        return EMPTY;
    }

    public static ResponseSchemaRegistry of(Collection<JobSpec.ResponseSchema> schemas) {
        if (schemas.isEmpty()) {
            return empty();
        }

        var byName = new LinkedHashMap<String, JobSpec.ResponseSchema>();
        for (var schema : schemas) {
            var name = schema.name().strip();
            if (name.isBlank()) {
                throw new IllegalArgumentException("responseSchema.name is required");
            }
            var error = JobResponseSchemaSupport.validate(schema);
            if (error.isPresent()) {
                throw new IllegalArgumentException(error.get());
            }
            if (byName.putIfAbsent(name, schema) != null) {
                throw new IllegalArgumentException("Duplicate response schema name: " + name);
            }
        }
        return new ResponseSchemaRegistry(Collections.unmodifiableMap(new LinkedHashMap<>(byName)));
    }

    public Optional<JobSpec.ResponseSchema> resolve(String name) {
        return Optional.ofNullable(schemasByName.get(name.strip()));
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(schemasByName.keySet());
    }
}
