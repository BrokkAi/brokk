package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.RustAnalyzer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

public final class RustExportUsageGraphStrategy implements UsageAnalyzer {
    private final Optional<RustAnalyzer> analyzer;
    private final ExportUsageReferenceGraphEngine.Limits limits;

    public RustExportUsageGraphStrategy(IAnalyzer analyzer) {
        this(resolveAnalyzer(analyzer), ExportUsageReferenceGraphEngine.Limits.defaults());
    }

    public RustExportUsageGraphStrategy(IAnalyzer analyzer, @Nullable ExportUsageReferenceGraphEngine.Limits limits) {
        this(resolveAnalyzer(analyzer), limits);
    }

    public RustExportUsageGraphStrategy(RustAnalyzer analyzer) {
        this(Optional.of(analyzer), ExportUsageReferenceGraphEngine.Limits.defaults());
    }

    public RustExportUsageGraphStrategy(
            RustAnalyzer analyzer, @Nullable ExportUsageReferenceGraphEngine.Limits limits) {
        this(Optional.of(analyzer), limits);
    }

    private RustExportUsageGraphStrategy(
            Optional<RustAnalyzer> analyzer, @Nullable ExportUsageReferenceGraphEngine.Limits limits) {
        this.analyzer = analyzer;
        this.limits = limits != null ? limits : ExportUsageReferenceGraphEngine.Limits.defaults();
    }

    public boolean canHandle(CodeUnit target) {
        if (analyzer.isEmpty()) {
            return false;
        }
        return !inferExportNames(target).isEmpty();
    }

    @Override
    public FuzzyResult findUsages(List<CodeUnit> overloads, Set<ProjectFile> candidateFiles, int maxUsages)
            throws InterruptedException {
        if (overloads.isEmpty()) {
            return new FuzzyResult.Success(Map.of());
        }

        CodeUnit target = overloads.getFirst();
        if (analyzer.isEmpty()) {
            return new FuzzyResult.Success(Map.of(target, Set.of()));
        }
        Set<String> exportNames = inferExportNames(target);
        if (exportNames.isEmpty()) {
            return new FuzzyResult.Success(Map.of(target, Set.of()));
        }

        int graphHitLimit = maxUsages == Integer.MAX_VALUE ? maxUsages : maxUsages + 1;
        var adapter = new RustExportUsageGraphAdapter(analyzer.orElseThrow());
        var effectiveLimits = new ExportUsageReferenceGraphEngine.Limits(
                limits.maxFiles(), Math.max(1, Math.min(limits.maxHits(), graphHitLimit)), limits.maxReexportDepth());
        Set<UsageHit> hits = new LinkedHashSet<>();
        for (String exportName : exportNames) {
            Set<ProjectFile> effectiveCandidateFiles = effectiveCandidateFiles(candidateFiles, exportNames, target);
            ReferenceGraphResult graphResult = ExportUsageReferenceGraphEngine.findExportUsages(
                    target.source(), exportName, target, adapter, effectiveLimits, effectiveCandidateFiles);
            hits.addAll(graphResult.hits().stream()
                    .map(hit -> new UsageHit(
                            hit.file(),
                            hit.range().startLine() + 1,
                            hit.range().startByte(),
                            hit.range().endByte(),
                            hit.enclosingUnit(),
                            hit.confidence(),
                            ""))
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
            if (hits.size() > maxUsages) {
                break;
            }
        }

        if (hits.size() > maxUsages) {
            return new FuzzyResult.TooManyCallsites(target.shortName(), hits.size(), maxUsages);
        }
        hits = hits.stream().limit(maxUsages).collect(Collectors.toCollection(LinkedHashSet::new));
        return new FuzzyResult.Success(Map.of(target, Set.copyOf(hits)));
    }

    private static Optional<RustAnalyzer> resolveAnalyzer(IAnalyzer analyzer) {
        return analyzer.subAnalyzer(Languages.RUST)
                .filter(RustAnalyzer.class::isInstance)
                .map(RustAnalyzer.class::cast);
    }

    private Set<ProjectFile> effectiveCandidateFiles(
            Set<ProjectFile> candidateFiles, Set<String> exportNames, CodeUnit target) {
        if (candidateFiles.isEmpty()) {
            return analyzer.orElseThrow().rustUsageCandidateFiles(exportNames, target);
        }
        Set<ProjectFile> analyzedFiles = analyzer.orElseThrow().getAnalyzedFiles();
        Set<ProjectFile> filtered =
                candidateFiles.stream().filter(analyzedFiles::contains).collect(Collectors.toUnmodifiableSet());
        return filtered.isEmpty() ? Set.of(target.source()) : filtered;
    }

    private Set<String> inferExportNames(CodeUnit target) {
        var exportNames = new LinkedHashSet<>(inferExportNames(target.source(), target.identifier()));
        analyzer.orElseThrow()
                .parentOf(target)
                .ifPresent(owner -> exportNames.addAll(inferExportNames(owner.source(), owner.identifier())));
        if (exportNames.isEmpty()
                && target.isFunction()
                && analyzer.orElseThrow().parentOf(target).isEmpty()) {
            exportNames.add(target.identifier());
        }
        return Set.copyOf(exportNames);
    }

    private Set<String> inferExportNames(ProjectFile definingFile, String localName) {
        ExportIndex exportIndex = analyzer.orElseThrow().exportIndexOf(definingFile);
        var exportNames = new LinkedHashSet<String>();
        if (exportIndex.exportsByName().containsKey(localName)) {
            exportNames.add(localName);
        }
        for (Map.Entry<String, ExportIndex.ExportEntry> e :
                exportIndex.exportsByName().entrySet()) {
            if (e.getValue() instanceof ExportIndex.LocalExport local && localName.equals(local.localName())) {
                exportNames.add(e.getKey());
            }
        }
        return Set.copyOf(exportNames);
    }
}
