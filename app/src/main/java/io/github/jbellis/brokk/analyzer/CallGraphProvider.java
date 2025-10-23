package io.github.jbellis.brokk.analyzer;

import java.util.List;
import java.util.Map;

/** Implemented by analyzers that can readily provide call graph analysis. */
public interface CallGraphProvider extends CapabilityProvider {

    Map<String, List<CallSite>> getCallgraphTo(String methodName, int depth);

    default Map<String, List<CallSite>> getCallgraphTo(CodeUnit method, int depth) {
        return getCallgraphTo(method.fqName(), depth);
    }

    Map<String, List<CallSite>> getCallgraphFrom(String methodName, int depth);

    default Map<String, List<CallSite>> getCallgraphFrom(CodeUnit method, int depth) {
        return getCallgraphFrom(method.fqName(), depth);
    }
}
