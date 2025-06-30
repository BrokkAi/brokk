package io.github.jbellis.brokk.testutil;

import io.github.jbellis.brokk.AnalyzerWrapper;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.*;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Mock AnalyzerWrapper that simulates a ready state for testing.
 * This class provides a test implementation that can be used across
 * multiple test classes that need an AnalyzerWrapper.
 */
public class MockAnalyzerWrapper extends AnalyzerWrapper {
    private final MockAnalyzer analyzer = new MockAnalyzer();

    public MockAnalyzerWrapper() {
        super(createTestProject(), createTaskRunner(), null);
    }
    
    private static TestProject createTestProject() {
        return new TestProject(Path.of("/tmp/test"),
                               Language.JAVA);
    }
    
    private static ContextManager.TaskRunner createTaskRunner() {
        return new ContextManager.TaskRunner() {
            @Override
            public <T> Future<T> submit(String taskDescription, Callable<T> task) {
                // Return a completed future for testing
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public IAnalyzer getNonBlocking() {
        return analyzer;
    }

    /**
     * Mock analyzer that returns test symbols for testing symbol badge functionality.
     */
    public static class MockAnalyzer implements IAnalyzer {
        @Override
        public Optional<CodeUnit> getDefinition(String fqName) {
            // Return a mock symbol for common test patterns
            if (fqName.matches(".*[A-Z].*")) { // Contains uppercase, likely a symbol
                return Optional.of(CodeUnit.cls(new ProjectFile(Path.of("/tmp/test"), "mock.java"),
                                               "", fqName));
            }
            return Optional.empty();
        }
    }
}
