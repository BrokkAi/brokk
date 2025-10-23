package io.github.jbellis.brokk.analyzer;

import java.util.Optional;
import java.util.Set;

/** Implemented by analyzers that can readily provide source code snippets. */
public interface SourceCodeProvider extends CapabilityProvider {

    Set<String> getMethodSources(String fqName, boolean includeComments);

    default Set<String> getMethodSources(CodeUnit method, boolean includeComments) {
        return getMethodSources(method.fqName(), includeComments);
    }

    /**
     * Gets the source code for a given method name. If multiple methods match (e.g. overloads), their source code
     * snippets are concatenated (separated by newlines). If none match, returns None.
     *
     * @param fqName the fully qualified method name
     * @param includeComments whether to include preceding comments in the source
     */
    default Optional<String> getMethodSource(String fqName, boolean includeComments) {
        var sources = getMethodSources(fqName, includeComments);
        if (sources.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(String.join("\n\n", sources));
    }

    default Optional<String> getMethodSource(CodeUnit method, boolean includeComments) {
        return getMethodSource(method.fqName(), includeComments);
    }

    /**
     * Gets the source code for the entire given class. If the class is partial or has multiple definitions, this
     * typically returns the primary definition.
     *
     * @param fqcn the fully qualified class name
     * @param includeComments whether to include preceding comments in the source
     */
    Optional<String> getClassSource(String fqcn, boolean includeComments);

    default Optional<String> getClassSource(CodeUnit classUnit, boolean includeComments) {
        return getClassSource(classUnit.fqName(), includeComments);
    }

    /**
     * Gets the source code for a given CodeUnit, dispatching to the appropriate method based on the unit type. This
     * allows analyzers to handle language-specific cases (e.g., TypeScript type aliases) internally.
     *
     * @param codeUnit the code unit to get source for
     * @param includeComments whether to include preceding comments in the source
     */
    Optional<String> getSourceForCodeUnit(CodeUnit codeUnit, boolean includeComments);
}
