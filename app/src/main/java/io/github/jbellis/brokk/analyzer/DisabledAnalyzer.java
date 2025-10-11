package io.github.jbellis.brokk.analyzer;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class DisabledAnalyzer implements IAnalyzer {

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public List<CodeUnit> getAllDeclarations() {
        return Collections.emptyList();
    }

    @Override
    public List<CodeUnit> getMembersInClass(String fqClass) {
        return Collections.emptyList();
    }

    @Override
    public Set<CodeUnit> getDeclarationsInFile(ProjectFile file) {
        return Collections.emptySet();
    }

    @Override
    public Optional<ProjectFile> getFileFor(String fqcn) {
        return Optional.empty();
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
    public List<CodeUnit> topLevelCodeUnitsOf(ProjectFile file) {
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
}
