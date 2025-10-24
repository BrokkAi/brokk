package io.github.jbellis.brokk.analyzer;

import java.util.List;
import java.util.Map;

/** Implemented by analyzers that can readily provide call graph analysis. */
public interface CallGraphProvider extends CapabilityProvider {

    /**
     * Get call graph showing what calls the given method.
     *
     * @param method the method to analyze
     * @param depth how many levels deep to traverse
     * @return map of caller methods to call sites
     */
    Map<String, List<CallSite>> getCallgraphTo(CodeUnit method, int depth);

    /**
     * Get call graph showing what the given method calls.
     *
     * @param method the method to analyze
     * @param depth how many levels deep to traverse
     * @return map of callee methods to call sites
     */
    Map<String, List<CallSite>> getCallgraphFrom(CodeUnit method, int depth);
}
