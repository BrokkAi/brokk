package io.github.jbellis.brokk.testutil;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.Language;

import java.nio.file.Path;

public final class TestContextManager implements IContextManager {
    private final TestProject project;
    private final IAnalyzer mockAnalyzer;

    public TestContextManager(Path projectRoot) {
        this.project = new TestProject(projectRoot, Language.JAVA);
        this.mockAnalyzer = new MockAnalyzer();
    }

    @Override
    public TestProject getProject() {
        return project;
    }

    @Override
    public IAnalyzer getAnalyzerUninterrupted() {
        return mockAnalyzer;
    }

    @Override
    public IAnalyzer getAnalyzer() {
        return mockAnalyzer;
    }

    /**
     * Mock analyzer implementation for testing that provides minimal functionality
     * to support fragment freezing without requiring a full CPG.
     */
    private static class MockAnalyzer implements IAnalyzer {
        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public boolean isCpg() {
            return false; // This will cause dynamic fragments to return placeholder text
        }

        @Override
        public java.util.List<io.github.jbellis.brokk.analyzer.CodeUnit> getUses(String fqName) {
            return java.util.List.of(); // Return empty list for test purposes
        }

        @Override
        public java.util.Optional<io.github.jbellis.brokk.analyzer.CodeUnit> getDefinition(String fqName) {
            return java.util.Optional.empty(); // Return empty for test purposes
        }

        @Override
        public java.util.Set<io.github.jbellis.brokk.analyzer.CodeUnit> getDeclarationsInFile(io.github.jbellis.brokk.analyzer.ProjectFile file) {
            return java.util.Set.of(); // Return empty set for test purposes
        }

        @Override
        public java.util.Optional<String> getSkeleton(String fqName) {
            return java.util.Optional.empty(); // Return empty for test purposes
        }

        @Override
        public java.util.Map<io.github.jbellis.brokk.analyzer.CodeUnit, String> getSkeletons(io.github.jbellis.brokk.analyzer.ProjectFile file) {
            return java.util.Map.of(); // Return empty map for test purposes
        }
    }
}
