package io.github.jbellis.brokk.analyzer;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Implemented by analyzers that can readily provide skeletons. */
public interface SkeletonProvider {

    /** return a summary of the given type or method */
    default Optional<String> getSkeleton(String fqName) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns just the class signature and field declarations, without method details. Used in symbol usages lookup.
     * (Show the "header" of the class that uses the referenced symbol in a field declaration.)
     */
    default Optional<String> getSkeletonHeader(String className) {
        throw new UnsupportedOperationException();
    }

    default Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        Map<CodeUnit, String> skeletons = new HashMap<>();
        if (this instanceof IAnalyzer analyzer) {
            for (CodeUnit symbol : analyzer.getDeclarationsInFile(file)) {
                Optional<String> skelOpt = getSkeleton(symbol.fqName());
                skelOpt.ifPresent(s -> skeletons.put(symbol, s));
            }
        }
        return skeletons;
    }
}
