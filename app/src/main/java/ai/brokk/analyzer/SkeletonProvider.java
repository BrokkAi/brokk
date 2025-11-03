package ai.brokk.analyzer;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Implemented by analyzers that can readily provide skeletons.
 *
 * <p><b>API Pattern:</b> Methods accept {@link CodeUnit} parameters. For String FQNs,
 * use {@link io.github.jbellis.brokk.AnalyzerUtil} convenience methods.
 */
public interface SkeletonProvider extends CapabilityProvider {

    /**
     * Return a summary of the given type or method.
     *
     * @param cu the code unit to get skeleton for
     * @return skeleton if available, empty otherwise
     */
    Optional<String> getSkeleton(CodeUnit cu);

    /**
     * Returns just the class signature and field declarations, without method details. Used in symbol usages lookup.
     * (Show the "header" of the class that uses the referenced symbol in a field declaration.)
     *
     * @param classUnit the class code unit to get header for
     * @return skeleton header if available, empty otherwise
     */
    Optional<String> getSkeletonHeader(CodeUnit classUnit);

    /**
     * Get skeletons for all top-level declarations in a file.
     *
     * @param file the file to get skeletons for
     * @return map of code units to their skeletons
     */
    default Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        final Map<CodeUnit, String> skeletons = new HashMap<>();
        if (this instanceof IAnalyzer analyzer) {
            for (CodeUnit symbol : analyzer.getTopLevelDeclarations(file)) {
                getSkeleton(symbol).ifPresent(s -> skeletons.put(symbol, s));
            }
        }
        return skeletons;
    }
}
