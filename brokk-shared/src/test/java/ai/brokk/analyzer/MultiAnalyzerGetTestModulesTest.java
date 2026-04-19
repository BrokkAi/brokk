package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.project.ICoreProject;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MultiAnalyzerGetTestModulesTest {

    @Test
    void testGetTestModulesDelegation() {
        Path root = Path.of("").toAbsolutePath();

        // 1. Create stubs
        IAnalyzer goStub = new StubAnalyzer() {
            @Override
            public List<String> getTestModules(Collection<ProjectFile> files) {
                return List.of("./callbacks");
            }
        };

        IAnalyzer pyStub = new StubAnalyzer() {
            @Override
            public List<String> getTestModules(Collection<ProjectFile> files) {
                return List.of("pkg");
            }
        };

        // 2. Setup MultiAnalyzer
        MultiAnalyzer multi = new MultiAnalyzer(Map.of(
                Languages.GO, goStub,
                Languages.PYTHON, pyStub));

        // 3. Prepare files
        ProjectFile goFile = new ProjectFile(root, "callbacks/x_test.go");
        ProjectFile pyFile = new ProjectFile(root, "app/test_x.py");

        // 4. Execute and Assert
        List<String> results = multi.getTestModules(List.of(goFile, pyFile));

        // Expect distinct and sorted: ["./callbacks", "pkg"]
        assertEquals(List.of("./callbacks", "pkg"), results);
    }

    /**
     * Minimal stub to avoid implementing all IAnalyzer methods.
     */
    private abstract static class StubAnalyzer implements IAnalyzer {
        @Override
        public List<CodeUnit> getTopLevelDeclarations(ProjectFile file) {
            return List.of();
        }

        @Override
        public Set<Language> languages() {
            return Set.of();
        }

        @Override
        public IAnalyzer update(Set<ProjectFile> changedFiles) {
            return this;
        }

        @Override
        public IAnalyzer update() {
            return this;
        }

        @Override
        public ICoreProject getProject() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CodeUnit> getAllDeclarations() {
            return List.of();
        }

        @Override
        public Set<CodeUnit> getDeclarations(ProjectFile file) {
            return Set.of();
        }

        @Override
        public java.util.SequencedSet<CodeUnit> getDefinitions(String fqName) {
            return new java.util.LinkedHashSet<>();
        }

        @Override
        public List<CodeUnit> getDirectChildren(CodeUnit cu) {
            return List.of();
        }

        @Override
        public Optional<String> extractCallReceiver(String reference) {
            return Optional.empty();
        }

        @Override
        public List<String> importStatementsOf(ProjectFile file) {
            return List.of();
        }

        @Override
        public Optional<CodeUnit> enclosingCodeUnit(ProjectFile file, Range range) {
            return Optional.empty();
        }

        @Override
        public Optional<CodeUnit> enclosingCodeUnit(ProjectFile file, int startLine, int endLine) {
            return Optional.empty();
        }

        @Override
        public List<Range> rangesOf(CodeUnit codeUnit) {
            return List.of();
        }

        @Override
        public Optional<String> getSkeleton(CodeUnit cu) {
            return Optional.empty();
        }

        @Override
        public Optional<String> getSkeletonHeader(CodeUnit classUnit) {
            return Optional.empty();
        }

        @Override
        public Optional<String> getSource(CodeUnit codeUnit, boolean includeComments) {
            return Optional.empty();
        }

        @Override
        public Set<String> getSources(CodeUnit codeUnit, boolean includeComments) {
            return Set.of();
        }
    }
}
