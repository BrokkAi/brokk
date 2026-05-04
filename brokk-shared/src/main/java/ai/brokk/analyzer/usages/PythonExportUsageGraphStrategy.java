package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.PythonAnalyzer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PythonExportUsageGraphStrategy implements UsageAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(PythonExportUsageGraphStrategy.class);

    private final PythonAnalyzer analyzer;
    private final PythonExportUsageGraphAdapter adapter;
    private final JsTsExportUsageReferenceGraph.Limits limits;

    public PythonExportUsageGraphStrategy(PythonAnalyzer analyzer) {
        this(analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());
    }

    public PythonExportUsageGraphStrategy(
            PythonAnalyzer analyzer, @Nullable JsTsExportUsageReferenceGraph.Limits limits) {
        this.analyzer = analyzer;
        this.adapter = new PythonExportUsageGraphAdapter(analyzer);
        this.limits = limits != null ? limits : JsTsExportUsageReferenceGraph.Limits.defaults();
    }

    public boolean canHandle(CodeUnit target) {
        Set<String> exportNames = inferExportNames(target.source(), target.identifier());
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
        Set<String> exportNames = inferExportNames(target.source(), target.identifier());
        if (exportNames.isEmpty()) {
            return new FuzzyResult.Success(Map.of(target, Set.of()));
        }

        Set<UsageHit> hits = new LinkedHashSet<>();
        for (String exportName : exportNames) {
            ReferenceGraphResult graphResult = JsTsExportUsageReferenceGraph.findExportUsages(
                    target.source(), exportName, target, adapter, limits, candidateFiles);
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
            if (hits.size() >= maxUsages) {
                break;
            }
        }

        hits = hits.stream().limit(maxUsages).collect(Collectors.toCollection(LinkedHashSet::new));
        return new FuzzyResult.Success(Map.of(target, Set.copyOf(hits)));
    }

    private Set<String> inferExportNames(ProjectFile definingFile, String localName) {
        ExportIndex exportIndex = analyzer.exportIndexOf(definingFile);
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
