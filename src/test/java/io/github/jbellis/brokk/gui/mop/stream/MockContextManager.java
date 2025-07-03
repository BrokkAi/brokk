package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.AnalyzerWrapper;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.testutil.MockAnalyzerWrapper;

/**
 * Mock ContextManager that provides a ready analyzer for testing
 */
public class MockContextManager implements IContextManager {
    private final MockAnalyzerWrapper analyzerWrapper = new MockAnalyzerWrapper();

    @Override
    public AnalyzerWrapper getAnalyzerWrapper() {
        return analyzerWrapper;
    }
}
