package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.JsTsAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

/**
 * JS/TS usages implementation backed by the exported-symbol reference graph.
 *
 * <p>This strategy is declaration-first: the entrypoint is a known {@link CodeUnit} definition and we only search
 * for usages of exported symbols that can be inferred from the defining file's {@link ExportIndex}.
 */
public final class JsTsExportUsageGraphStrategy implements UsageAnalyzer {

    private final IAnalyzer analyzer;
    private final JsTsExportUsageReferenceGraph.Limits limits;

    public JsTsExportUsageGraphStrategy(IAnalyzer analyzer) {
        this(analyzer, JsTsExportUsageReferenceGraph.Limits.defaults());
    }

    public JsTsExportUsageGraphStrategy(IAnalyzer analyzer, @Nullable JsTsExportUsageReferenceGraph.Limits limits) {
        this.analyzer = analyzer;
        this.limits = limits != null ? limits : JsTsExportUsageReferenceGraph.Limits.defaults();
    }

    public boolean canHandle(CodeUnit target) {
        if (!(analyzer instanceof JsTsAnalyzer jsTs)) {
            return false;
        }
        return inferQuerySeed(jsTs, target).isPresent();
    }

    @Override
    public FuzzyResult findUsages(List<CodeUnit> overloads, Set<ProjectFile> candidateFiles, int maxUsages)
            throws InterruptedException {
        if (overloads.isEmpty()) {
            return new FuzzyResult.Success(Map.of());
        }

        CodeUnit target = overloads.getFirst();
        if (!(analyzer instanceof JsTsAnalyzer jsTs)) {
            return new FuzzyResult.Success(Map.of(target, Set.of()));
        }

        Optional<QuerySeed> querySeedOpt = inferQuerySeed(jsTs, target);
        if (querySeedOpt.isEmpty()) {
            return new FuzzyResult.Success(Map.of(target, Set.of()));
        }
        QuerySeed querySeed = querySeedOpt.orElseThrow();

        ReferenceGraphResult graphResult = JsTsExportUsageReferenceGraph.findExportUsages(
                target.source(), querySeed.exportName(), target, analyzer, limits, candidateFiles);

        Set<UsageHit> hits = graphResult.hits().stream()
                .map(hit -> {
                    int line = hit.range().startLine() + 1;
                    String snippet = ""; // v1: can be added later if UI needs it
                    return new UsageHit(
                            hit.file(),
                            line,
                            hit.range().startByte(),
                            hit.range().endByte(),
                            hit.enclosingUnit(),
                            hit.confidence(),
                            snippet);
                })
                .limit(maxUsages)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return new FuzzyResult.Success(Map.of(target, Set.copyOf(hits)));
    }

    private record QuerySeed(ProjectFile definingFile, String exportName) {}

    private static Optional<QuerySeed> inferQuerySeed(JsTsAnalyzer analyzer, CodeUnit target) {
        Optional<String> exportName = inferExportName(analyzer, target.source(), target.identifier());
        if (exportName.isPresent()) {
            return Optional.of(new QuerySeed(target.source(), exportName.orElseThrow()));
        }

        String ownerName = ownerNameOf(target);
        if (ownerName.isEmpty()) {
            return Optional.empty();
        }

        return inferExportName(analyzer, target.source(), ownerName).map(name -> new QuerySeed(target.source(), name));
    }

    private static Optional<String> inferExportName(JsTsAnalyzer analyzer, ProjectFile definingFile, String localName) {
        ExportIndex exportIndex = analyzer.exportIndexOf(definingFile);

        if (exportIndex.exportsByName().containsKey(localName)) {
            return Optional.of(localName);
        }

        for (Map.Entry<String, ExportIndex.ExportEntry> e :
                exportIndex.exportsByName().entrySet()) {
            String exportedName = e.getKey();
            ExportIndex.ExportEntry entry = e.getValue();
            if (entry instanceof ExportIndex.LocalExport le && localName.equals(le.localName())) {
                return Optional.of(exportedName);
            }
            if (entry instanceof ExportIndex.DefaultExport def
                    && def.localName() != null
                    && localName.equals(def.localName())) {
                return Optional.of("default");
            }
        }

        return Optional.empty();
    }

    private static String ownerNameOf(CodeUnit target) {
        String shortName = target.shortName();
        int lastDot = shortName.lastIndexOf('.');
        if (lastDot <= 0) {
            return "";
        }
        return shortName.substring(0, lastDot);
    }
}
