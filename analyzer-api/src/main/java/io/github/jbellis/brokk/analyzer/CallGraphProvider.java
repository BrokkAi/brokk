package io.github.jbellis.brokk.analyzer;

import java.util.List;
import java.util.Map;

/** Implemented by analyzers that can readily provide call graph analysis. */
public interface CallGraphProvider {

    default Map<String, List<CallSite>> getCallgraphTo(String methodName, int depth) {
        throw new UnsupportedOperationException();
    }

    default Map<String, List<CallSite>> getCallgraphFrom(String methodName, int depth) {
        throw new UnsupportedOperationException();
    }
}
