package io.github.jbellis.brokk.analyzer;

import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/** Implemented by analyzers that can readily provide source code snippets. */
public interface SourceCodeProvider {

    /**
     * Gets the source code for a given method name. If multiple methods match (e.g. overloads), their source code
     * snippets are concatenated (separated by newlines). If none match, returns None.
     */
    default Optional<String> getMethodSource(String fqName) {
        throw new UnsupportedOperationException();
    }

    /**
     * Gets the source code for the entire given class. If the class is partial or has multiple definitions, this
     * typically returns the primary definition.
     */
    default @Nullable String getClassSource(String fqcn) {
        throw new UnsupportedOperationException();
    }
}
