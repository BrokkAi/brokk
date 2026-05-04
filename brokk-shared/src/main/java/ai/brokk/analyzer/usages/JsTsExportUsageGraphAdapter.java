package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ImportAnalysisProvider;
import ai.brokk.analyzer.JsTsAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

public final class JsTsExportUsageGraphAdapter implements ExportUsageGraphLanguageAdapter {
    private final JsTsAnalyzer analyzer;

    public JsTsExportUsageGraphAdapter(JsTsAnalyzer analyzer) {
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
    public Set<CodeUnit> definitionsOf(String localName) {
        return analyzer.getDefinitions(localName);
    }

    @Override
    public Set<ResolvedReceiverCandidate> resolvedReceiverCandidatesOf(ProjectFile file, ImportBinder binder) {
        return analyzer.resolvedReceiverCandidatesOf(file, binder);
    }

    @Override
    public ResolutionOutcome resolveModule(ProjectFile importingFile, String moduleSpecifier) {
        var outcome = analyzer.resolveEsmModuleOutcome(importingFile, moduleSpecifier);
        return new ResolutionOutcome(outcome.resolved(), outcome.externalFrontier());
    }

    @Override
    public Map<ProjectFile, Set<ProjectFile>> reverseReexportIndex() {
        return analyzer.reverseReexportIndex();
    }

    @Override
    public Set<ProjectFile> referencingFilesOf(ProjectFile file) {
        var providerOpt = analyzer.as(ImportAnalysisProvider.class);
        return providerOpt.map(provider -> provider.referencingFilesOf(file)).orElse(Set.of());
    }

    @Override
    public void ensureImportReverseIndexPopulated() throws InterruptedException {
        analyzer.ensureImportReverseIndexPopulated();
    }

    @Override
    public Map<String, Set<String>> heritageIndex() {
        return analyzer.heritageIndex();
    }

    @Override
    public @Nullable CodeUnit exactMember(
            ProjectFile sourceFile, String ownerClassName, String memberName, boolean instanceReceiver) {
        return analyzer.memberResolutionIndex(sourceFile)
                .get(new JsTsAnalyzer.MemberLookupKey(ownerClassName, memberName, instanceReceiver));
    }

    @Override
    public ExportResolutionData cachedExportResolution(
            ProjectFile definingFile,
            String exportName,
            int maxReexportDepth,
            Supplier<ExportResolutionData> supplier) {
        var cached = analyzer.cachedExportResolution(definingFile, exportName, maxReexportDepth, () -> {
            var computed = supplier.get();
            return new JsTsAnalyzer.ExportResolutionData(
                    computed.targets(), computed.frontier(), computed.externalFrontier());
        });
        return new ExportResolutionData(cached.targets(), cached.frontier(), cached.externalFrontier());
    }
}
