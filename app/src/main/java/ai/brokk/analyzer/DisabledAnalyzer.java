package ai.brokk.analyzer;

import ai.brokk.IProject;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public class DisabledAnalyzer implements IAnalyzer {
    @Nullable
    private final IProject project;

    public DisabledAnalyzer() {
        this(null);
    }

    public DisabledAnalyzer(@Nullable IProject project) {
        this.project = project;
    }

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
    public Set<CodeUnit> searchDefinitions(String pattern) {
        return Set.of();
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

    @Override
    public Optional<CodeUnit> getDefinition(String fqName) {
        return Optional.empty();
    }

    @Override
    public IProject getProject() {
        if (project == null) {
            throw new UnsupportedOperationException("DisabledAnalyzer has no project");
        }
        return project;
    }

    @Override
    public Set<Language> languages() {
        return Set.of();
    }
}
