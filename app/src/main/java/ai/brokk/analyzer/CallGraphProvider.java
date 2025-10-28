package ai.brokk.analyzer;

import java.util.List;
import java.util.Map;

/** Implemented by analyzers that can readily provide call graph analysis. */
public interface CallGraphProvider extends CapabilityProvider {

    Map<String, List<CallSite>> getCallgraphTo(String methodName, int depth);

    Map<String, List<CallSite>> getCallgraphFrom(String methodName, int depth);
}
