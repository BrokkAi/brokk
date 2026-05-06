package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.RustAnalyzer;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class RustExportUsageGraphAdapter implements ExportUsageGraphLanguageAdapter {
    private final RustAnalyzer analyzer;

    public RustExportUsageGraphAdapter(RustAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @Override
    public ExportIndex exportIndexOf(ProjectFile file) {
        return analyzer.exportIndexOf(file);
    }

    @Override
    public ImportBinder importBinderOf(ProjectFile file) {
        return analyzer.importBinderOf(file);
    }

    @Override
    public Set<ReferenceCandidate> usageCandidatesOf(ProjectFile file, ImportBinder binder) {
        return analyzer.exportUsageCandidatesOf(file, binder);
    }

    @Override
    public Set<ResolvedReceiverCandidate> resolvedReceiverCandidatesOf(ProjectFile file, ImportBinder binder) {
        return analyzer.resolvedReceiverCandidatesOf(file, binder);
    }

    @Override
    public Set<CodeUnit> definitionsOf(ProjectFile file, String localName) {
        return analyzer.getAllDeclarations().stream()
                .filter(cu -> cu.source().equals(file))
                .filter(cu ->
                        cu.identifier().equals(localName) || cu.shortName().equals(localName))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public ResolutionOutcome resolveModule(ProjectFile importingFile, String moduleSpecifier) {
        return analyzer.resolveRustModuleOutcome(importingFile, moduleSpecifier);
    }

    @Override
    public Set<ProjectFile> referencingFilesOf(ProjectFile file) {
        return analyzer.referencingFilesOf(file);
    }

    @Override
    public Map<String, Set<String>> heritageIndex() {
        return analyzer.heritageIndex();
    }

    @Override
    public @Nullable CodeUnit exactMember(
            ProjectFile sourceFile, String ownerClassName, String memberName, boolean instanceReceiver) {
        return analyzer.exactMember(sourceFile, ownerClassName, memberName, instanceReceiver);
    }
}
