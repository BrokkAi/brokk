package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.PythonAnalyzer;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

public final class PythonExportUsageGraphAdapter implements ExportUsageGraphLanguageAdapter {
    private final PythonAnalyzer analyzer;
    private final Map<ModuleResolutionKey, ResolutionOutcome> moduleResolutionCache = new HashMap<>();

    public PythonExportUsageGraphAdapter(PythonAnalyzer analyzer) {
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
        return analyzer.exportUsageCandidatesOf(file);
    }

    @Override
    public Set<ResolvedReceiverCandidate> resolvedReceiverCandidatesOf(ProjectFile file, ImportBinder binder) {
        return analyzer.resolvedReceiverCandidatesOf(file, binder);
    }

    @Override
    public Set<CodeUnit> definitionsOf(ProjectFile file, String localName) {
        var definitions = new LinkedHashSet<CodeUnit>();
        definitions.addAll(analyzer.getDefinitions(localName));
        definitions.addAll(analyzer.definitionsByIdentifier(localName));
        return definitions.stream()
                .filter(cu -> cu.source().equals(file))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public ResolutionOutcome resolveModule(ProjectFile importingFile, String moduleSpecifier) {
        return moduleResolutionCache.computeIfAbsent(new ModuleResolutionKey(importingFile, moduleSpecifier), key -> {
            var outcome = analyzer.resolvePythonModuleOutcome(key.importingFile(), key.moduleSpecifier());
            return new ResolutionOutcome(outcome.resolved(), outcome.externalFrontier());
        });
    }

    @Override
    public ResolutionOutcome resolveImportedSubmodule(ProjectFile importingFile, ImportBinder.ImportBinding binding) {
        String importedName = binding.importedName();
        if (importedName == null || importedName.isBlank()) {
            return ResolutionOutcome.empty();
        }
        return resolveModule(importingFile, submoduleSpecifier(binding.moduleSpecifier(), importedName));
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
    public List<CodeUnit> ancestorsOf(CodeUnit ownerClass) {
        return analyzer.getAncestors(ownerClass);
    }

    @Override
    public @Nullable CodeUnit exactMember(
            ProjectFile sourceFile, String ownerClassName, String memberName, boolean instanceReceiver) {
        return analyzer.exactMember(sourceFile, new PythonAnalyzer.MemberKey(ownerClassName, memberName));
    }

    @Override
    public ExportResolutionData cachedExportResolution(
            ProjectFile definingFile,
            String exportName,
            int maxReexportDepth,
            Supplier<ExportResolutionData> supplier) {
        return analyzer.cachedExportResolution(definingFile, exportName, maxReexportDepth, supplier);
    }

    private static String submoduleSpecifier(String moduleSpecifier, String importedName) {
        return moduleSpecifier.chars().allMatch(ch -> ch == '.')
                ? moduleSpecifier + importedName
                : moduleSpecifier + "." + importedName;
    }

    private record ModuleResolutionKey(ProjectFile importingFile, String moduleSpecifier) {}
}
