package io.github.jbellis.brokk.analyzer;

import java.util.Optional;

/** Implemented by analyzers that can readily provide source code snippets. */
public interface SourceCodeProvider extends CapabilityProvider {

    /**
     * Gets the source code for a given method name. If multiple methods match (e.g. overloads), their source code
     * snippets are concatenated (separated by newlines). If none match, returns None.
     */
    Optional<String> getMethodSource(String fqName);

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
