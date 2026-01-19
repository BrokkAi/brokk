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
    Optional<String> getSource(CodeUnit codeUnit, boolean includeComments);

    /**
     * Gets all source code versions for a given CodeUnit. For methods, this includes overloads.
     * For classes, this typically returns a singleton set.
     *
     * @param codeUnit the code unit to get sources for
     * @param includeComments whether to include preceding comments in the source
     * @return set of source code snippets, empty set if none found
     */
    Set<String> getSources(CodeUnit codeUnit, boolean includeComments);
}
