package ai.brokk.analyzer;

import java.util.Optional;
import java.util.Set;

/**
 * Implemented by analyzers that can readily provide source code snippets.
 *
 * <p><b>API Pattern:</b> Methods accept {@link CodeUnit} parameters. For String FQNs,
 * use {@link io.github.jbellis.brokk.AnalyzerUtil} convenience methods.
 */
public interface SourceCodeProvider extends CapabilityProvider {

    /**
     * Gets the source code for a given CodeUnit.
     *
     * @param codeUnit the code unit to get source for
     * @param includeComments whether to include preceding comments in the source
     * @return source code if found, empty otherwise
     */
    default Optional<String> getSource(CodeUnit codeUnit, boolean includeComments) {
        return getSourceForCodeUnit(codeUnit, includeComments);
    }

    /**
     * Gets all source code versions for a given CodeUnit. For methods, this includes overloads.
     * For classes, this typically returns a singleton set.
     *
     * @param codeUnit the code unit to get sources for
     * @param includeComments whether to include preceding comments in the source
     * @return set of source code snippets, empty set if none found
     */
    default Set<String> getSources(CodeUnit codeUnit, boolean includeComments) {
        if (codeUnit.isFunction()) {
            return getMethodSources(codeUnit, includeComments);
        }
        if (codeUnit.isClass()) {
            return getClassSource(codeUnit, includeComments).map(Set::of).orElse(Set.of());
        }
        return Set.of();
    }

    /**
     * Gets all source code versions for a given method. If multiple methods match (e.g. overloads), returns a set with
     * all matching source snippets.
     *
     * @param method the method code unit to get sources for
     * @param includeComments whether to include preceding comments in the source
     * @return set of source code snippets, empty set if none found
     * @deprecated Use {@link #getSources(CodeUnit, boolean)} instead.
     */
    @Deprecated
    Set<String> getMethodSources(CodeUnit method, boolean includeComments);

    /**
     * Gets the source code for a given method. If multiple methods match (e.g. overloads), their source code snippets
     * are concatenated (separated by double newlines).
     *
     * @param method the method code unit to get source for
     * @param includeComments whether to include preceding comments in the source
     * @return concatenated source code if found, empty otherwise
     * @deprecated Use {@link #getSource(CodeUnit, boolean)} instead.
     */
    @Deprecated
    default Optional<String> getMethodSource(CodeUnit method, boolean includeComments) {
        var sources = getMethodSources(method, includeComments);
        if (sources.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(String.join("\n\n", sources));
    }

    /**
     * Gets the source code for the entire given class. If the class is partial or has multiple definitions, this
     * typically returns the primary definition.
     *
     * @param classUnit the class code unit to get source for
     * @param includeComments whether to include preceding comments in the source
     * @return class source code if found, empty otherwise
     * @deprecated Use {@link #getSource(CodeUnit, boolean)} instead.
     */
    @Deprecated
    Optional<String> getClassSource(CodeUnit classUnit, boolean includeComments);

    /**
     * Gets the source code for a given CodeUnit, dispatching to the appropriate method based on the unit type. This
     * allows analyzers to handle language-specific cases (e.g., TypeScript type aliases) internally.
     *
     * @param codeUnit the code unit to get source for
     * @param includeComments whether to include preceding comments in the source
     * @return source code if found, empty otherwise
     * @deprecated Use {@link #getSource(CodeUnit, boolean)} instead.
     */
    @Deprecated
    Optional<String> getSourceForCodeUnit(CodeUnit codeUnit, boolean includeComments);
}
