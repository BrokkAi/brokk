package io.github.jbellis.brokk.analyzer;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Implemented by analyzers that can readily provide skeletons. */
public interface SkeletonProvider extends CapabilityProvider {

    /** return a summary of the given type or method */
    Optional<String> getSkeleton(String fqName);

    /**
     * Returns just the class signature and field declarations, without method details. Used in symbol usages lookup.
     * (Show the "header" of the class that uses the referenced symbol in a field declaration.)
     */
    Optional<String> getSkeletonHeader(String className);

    default Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        final Map<CodeUnit, String> skeletons = new HashMap<>();
        if (this instanceof IAnalyzer analyzer) {
            for (CodeUnit symbol : analyzer.getTopLevelDeclarations(file)) {
                getSkeleton(symbol.fqName()).ifPresent(s -> skeletons.put(symbol, s));
            }
        }
        return skeletons;
    }
}
