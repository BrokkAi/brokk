package ai.brokk.analyzer;

import java.util.List;
import java.util.Map;

/**
 * Implemented by analyzers that can readily provide call graph analysis.
 *
 * <p><b>API Pattern:</b> Methods accept {@link CodeUnit} parameters. For String FQNs,
 * use {@link io.github.jbellis.brokk.AnalyzerUtil} convenience methods.
 */
public interface CallGraphProvider extends CapabilityProvider {

    /**
     * Get call graph showing what calls the given method.
     * For overloaded methods, operates on all overloads together.
     *
     * @param method the method to analyze
     * @param depth how many levels deep to traverse
     * @return map of caller methods to call sites
     */
    Map<String, List<CallSite>> getCallgraphTo(CodeUnit method, int depth);

    /**
     * Get call graph showing what the given method calls.
     * For overloaded methods, operates on all overloads together.
     *
     * @param method the method to analyze
     * @param depth how many levels deep to traverse
     * @return map of callee methods to call sites
     */
    Map<String, List<CallSite>> getCallgraphFrom(CodeUnit method, int depth);
}
