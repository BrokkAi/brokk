package io.github.jbellis.brokk.analyzer;

import java.util.Optional;
import java.util.Set;

/** Implemented by analyzers that can readily provide source code snippets. */
public interface SourceCodeProvider extends CapabilityProvider {

    Set<String> getMethodSources(String fqName);

    /**
     * Gets the source code for a given method name. If multiple methods match (e.g. overloads), their source code
     * snippets are concatenated (separated by newlines). If none match, returns None.
     */
    default Optional<String> getMethodSource(String fqName) {
        var sources = getMethodSources(fqName);
        if (sources.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(String.join("\n\n", sources));
    }

    /**
     * Gets the source code for the entire given class. If the class is partial or has multiple definitions, this
     * typically returns the primary definition.
     */
    Optional<String> getClassSource(String fqcn);

    /**
     * Gets the source code for a given CodeUnit, dispatching to the appropriate method based on the unit type. This
     * allows analyzers to handle language-specific cases (e.g., TypeScript type aliases) internally.
     */
    Optional<String> getSourceForCodeUnit(CodeUnit codeUnit);
}
