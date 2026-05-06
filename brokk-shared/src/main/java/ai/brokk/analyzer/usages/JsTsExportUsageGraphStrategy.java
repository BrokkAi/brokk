package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.JsTsAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JS/TS usages implementation backed by the exported-symbol reference graph.
 *
 * <p>This strategy is declaration-first: the entrypoint is a known {@link CodeUnit} definition and we only search
 * for usages of exported symbols that can be inferred from the defining file's {@link ExportIndex}.
 */
public final class JsTsExportUsageGraphStrategy implements UsageAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(JsTsExportUsageGraphStrategy.class);

    private final IAnalyzer analyzer;
    private final ExportUsageReferenceGraphEngine.Limits limits;

    public JsTsExportUsageGraphStrategy(IAnalyzer analyzer) {
        this(analyzer, ExportUsageReferenceGraphEngine.Limits.defaults());
    }

    public JsTsExportUsageGraphStrategy(IAnalyzer analyzer, @Nullable ExportUsageReferenceGraphEngine.Limits limits) {
        this.analyzer = analyzer;
        this.limits = limits != null ? limits : ExportUsageReferenceGraphEngine.Limits.defaults();
    }

    public boolean canHandle(CodeUnit target) {
        if (!(analyzer instanceof JsTsAnalyzer jsTs)) {
            return false;
        }
        Set<QuerySeed> seeds = inferQuerySeeds(jsTs, target);
        log.debug("JS/TS graph canHandle {} -> {} seeds {}", target.fqName(), seeds.size(), seeds);
        return !seeds.isEmpty();
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

        Set<QuerySeed> querySeeds = inferQuerySeeds(jsTs, target);
        if (querySeeds.isEmpty()) {
            log.debug("JS/TS graph found no query seeds for {}", target.fqName());
            return new FuzzyResult.Success(Map.of(target, Set.of()));
        }

        log.debug(
                "JS/TS graph analyzing {} with seeds {} over {} candidate files",
                target.fqName(),
                querySeeds,
                candidateFiles.size());

        Set<UsageHit> hits = new LinkedHashSet<>();
        for (QuerySeed querySeed : querySeeds) {
            ReferenceGraphResult graphResult = ExportUsageReferenceGraphEngine.findExportUsages(
                    querySeed.definingFile(),
                    querySeed.exportName(),
                    target,
                    new JsTsExportUsageGraphAdapter(jsTs),
                    limits,
                    candidateFiles);

            hits.addAll(graphResult.hits().stream()
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
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
            if (hits.size() >= maxUsages) {
                break;
            }
        }
        hits = hits.stream().limit(maxUsages).collect(Collectors.toCollection(LinkedHashSet::new));
        log.debug("JS/TS graph produced {} hits for {}", hits.size(), target.fqName());

        return new FuzzyResult.Success(Map.of(target, Set.copyOf(hits)));
    }

    private record QuerySeed(ProjectFile definingFile, String exportName) {}

    private static Set<QuerySeed> inferQuerySeeds(JsTsAnalyzer analyzer, CodeUnit target) {
        Set<QuerySeed> seeds = inferQuerySeeds(analyzer, target.source(), target.identifier());
        if (!seeds.isEmpty()) {
            return seeds;
        }
        String ownerName = ownerNameOf(target);
        if (ownerName.isEmpty()) {
            return Set.of();
        }
        return inferQuerySeeds(analyzer, target.source(), ownerName);
    }

    private static Set<QuerySeed> inferQuerySeeds(JsTsAnalyzer analyzer, ProjectFile definingFile, String localName) {
        Set<String> directExportNames = inferExportNames(analyzer, definingFile, localName);
        if (directExportNames.isEmpty()) {
            return Set.of();
        }

        var seeds = new LinkedHashSet<QuerySeed>();
        var queue = new ArrayDeque<QuerySeed>();
        var visited = new HashSet<QuerySeed>();
        directExportNames.stream()
                .map(name -> new QuerySeed(definingFile, name))
                .forEach(queue::addLast);

        while (!queue.isEmpty()) {
            QuerySeed current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            seeds.add(current);
            analyzer
                    .reverseExportSeedIndex()
                    .getOrDefault(
                            new JsTsAnalyzer.ReverseExportSeedKey(current.definingFile(), current.exportName()),
                            Set.of())
                    .stream()
                    .map(seed -> new QuerySeed(seed.file(), seed.exportName()))
                    .forEach(queue::addLast);
        }
        return Set.copyOf(seeds);
    }

    private static Set<String> inferExportNames(JsTsAnalyzer analyzer, ProjectFile definingFile, String localName) {
        ExportIndex exportIndex = analyzer.exportIndexOf(definingFile);
        var exportNames = new LinkedHashSet<String>();

        if (exportIndex.exportsByName().containsKey(localName)) {
            exportNames.add(localName);
        }

        for (Map.Entry<String, ExportIndex.ExportEntry> e :
                exportIndex.exportsByName().entrySet()) {
            String exportedName = e.getKey();
            ExportIndex.ExportEntry entry = e.getValue();
            if (entry instanceof ExportIndex.LocalExport le && localName.equals(le.localName())) {
                exportNames.add(exportedName);
            }
            if (entry instanceof ExportIndex.DefaultExport def
                    && def.localName() != null
                    && localName.equals(def.localName())) {
                exportNames.add("default");
            }
        }

        return Set.copyOf(exportNames);
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
