package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.PythonAnalyzer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PythonExportUsageGraphStrategy implements UsageAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(PythonExportUsageGraphStrategy.class);

    private final Optional<PythonAnalyzer> analyzer;
    private final ExportUsageReferenceGraphEngine.Limits limits;

    public PythonExportUsageGraphStrategy(IAnalyzer analyzer) {
        this(resolveAnalyzer(analyzer), ExportUsageReferenceGraphEngine.Limits.defaults());
    }

    public PythonExportUsageGraphStrategy(IAnalyzer analyzer, @Nullable ExportUsageReferenceGraphEngine.Limits limits) {
        this(resolveAnalyzer(analyzer), limits);
    }

    public PythonExportUsageGraphStrategy(PythonAnalyzer analyzer) {
        this(Optional.of(analyzer), ExportUsageReferenceGraphEngine.Limits.defaults());
    }

    public PythonExportUsageGraphStrategy(
            PythonAnalyzer analyzer, @Nullable ExportUsageReferenceGraphEngine.Limits limits) {
        this(Optional.of(analyzer), limits);
    }

    private PythonExportUsageGraphStrategy(
            Optional<PythonAnalyzer> analyzer, @Nullable ExportUsageReferenceGraphEngine.Limits limits) {
        this.analyzer = analyzer;
        this.limits = limits != null ? limits : ExportUsageReferenceGraphEngine.Limits.defaults();
    }

    public boolean canHandle(CodeUnit target) {
        if (analyzer.isEmpty()) {
            return false;
        }
        Set<String> exportNames = inferExportNames(target);
        log.debug("Python graph canHandle {} -> export names {}", target.fqName(), exportNames);
        return !exportNames.isEmpty();
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
        var adapter = new PythonExportUsageGraphAdapter(analyzer.orElseThrow());
        var effectiveLimits = new ExportUsageReferenceGraphEngine.Limits(
                limits.maxFiles(), Math.max(1, Math.min(limits.maxHits(), graphHitLimit)), limits.maxReexportDepth());
        Set<UsageHit> hits = new LinkedHashSet<>();
        for (String exportName : exportNames) {
            ReferenceGraphResult graphResult = ExportUsageReferenceGraphEngine.findExportUsages(
                    target.source(), exportName, target, adapter, effectiveLimits, candidateFiles);
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

    private static Optional<PythonAnalyzer> resolveAnalyzer(IAnalyzer analyzer) {
        return analyzer.subAnalyzer(Languages.PYTHON)
                .filter(PythonAnalyzer.class::isInstance)
                .map(PythonAnalyzer.class::cast);
    }

    private Set<String> inferExportNames(CodeUnit target) {
        Set<String> exportNames = inferExportNames(target.source(), target.identifier());
        if (!exportNames.isEmpty()) {
            return exportNames;
        }
        String ownerName = ownerNameOf(target);
        if (ownerName.isEmpty()) {
            return Set.of();
        }
        return inferExportNames(target.source(), ownerName);
    }

    private static String ownerNameOf(CodeUnit target) {
        String shortName = target.shortName();
        int lastDot = shortName.lastIndexOf('.');
        if (lastDot <= 0 || (!target.isFunction() && !target.isField())) {
            return "";
        }
        return shortName.substring(0, lastDot);
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
