package io.github.jbellis.brokk.testutil;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.InMemoryRepo;
import io.github.jbellis.brokk.IConsoleIO;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class TestContextManager implements IContextManager {
    private final TestProject project;
    private final IAnalyzer mockAnalyzer;
    private final InMemoryRepo inMemoryRepo;
    private final Set<ProjectFile> editableFiles = new HashSet<>();
    private final Set<ProjectFile> readonlyFiles = new HashSet<>();
    private final IConsoleIO consoleIO;

    public TestContextManager(Path projectRoot) {
        this(projectRoot, null);
    }

    public TestContextManager(Path projectRoot, IConsoleIO consoleIO) {
        this.project = new TestProject(projectRoot, Language.JAVA);
        this.mockAnalyzer = new MockAnalyzer();
        this.inMemoryRepo = new InMemoryRepo();
        this.consoleIO = consoleIO;
    }

    @Override
    public TestProject getProject() {
        return project;
    }

    @Override
    public InMemoryRepo getRepo() {
        return inMemoryRepo;
    }

    @Override
    public Set<ProjectFile> getEditableFiles() {
        return new HashSet<>(editableFiles);
    }

    @Override
    public Set<BrokkFile> getReadonlyFiles() {
        // Assuming ProjectFile is a subtype of BrokkFile,
        // new HashSet<>(readonlyFiles) which is Set<ProjectFile>
        // can be returned as Set<BrokkFile>.
        return new HashSet<>(readonlyFiles);
    }

    public void addEditableFile(ProjectFile file) {
        this.editableFiles.add(file);
        this.readonlyFiles.remove(file); // Cannot be both
    }

    public void addReadonlyFile(ProjectFile file) {
        this.readonlyFiles.add(file);
        this.editableFiles.remove(file); // Cannot be both
    }

    public void clearEditableAndReadOnlyFiles() {
        this.editableFiles.clear();
        this.readonlyFiles.clear();
    }
    
    @Override
    public ProjectFile toFile(String relName) {
        return new ProjectFile(project.getRoot(), relName);
    }

    @Override
    public IAnalyzer getAnalyzerUninterrupted() {
        return mockAnalyzer;
    }

    @Override
    public IAnalyzer getAnalyzer() {
        return mockAnalyzer;
    }

    @Override
    public IConsoleIO getIo() {
        if (consoleIO == null) {
            // Fallback for existing tests that don't pass IConsoleIO,
            // though the interface default would throw UnsupportedOperationException.
            // Consider making IConsoleIO mandatory in constructor if all tests are updated.
            throw new UnsupportedOperationException("IConsoleIO not provided to TestContextManager");
        }
        return consoleIO;
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
