package ai.brokk.analyzer;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class DisabledAnalyzer implements IAnalyzer {

    @Override
    public List<CodeUnit> getAllDeclarations() {
        return Collections.emptyList();
    }

    @Override
    public List<CodeUnit> getMembersInClass(CodeUnit classUnit) {
        return Collections.emptyList();
    }

    @Override
    public Set<CodeUnit> getDeclarations(ProjectFile file) {
        return Collections.emptySet();
    }

    @Override
    public List<CodeUnit> searchDefinitions(String pattern) {
        return Collections.emptyList();
    }

    @Override
    public Set<String> getSymbols(Set<CodeUnit> sources) {
        return Collections.emptySet();
    }

    @Override
    public List<CodeUnit> getTopLevelDeclarations(ProjectFile file) {
        return Collections.emptyList();
    }

    @Override
    public List<CodeUnit> getSubDeclarations(CodeUnit cu) {
        return Collections.emptyList();
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
    public IAnalyzer update(Set<ProjectFile> changedFiles) {
        return update();
    }

    @Override
    public IAnalyzer update() {
        return new DisabledAnalyzer();
    }
}
