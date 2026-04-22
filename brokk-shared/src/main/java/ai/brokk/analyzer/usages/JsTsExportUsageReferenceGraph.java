package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ImportAnalysisProvider;
import ai.brokk.analyzer.JsTsAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JS/TS v1: flow-insensitive exported-symbol usages.
 *
 * <p>This is intentionally built on top of language-agnostic IR types ({@link ReferenceCandidate}, {@link ReferenceHit})
 * so that other languages can plug into the same orchestration later.
 */
public final class JsTsExportUsageReferenceGraph {

    private JsTsExportUsageReferenceGraph() {}

    public static ExportUsageReferenceGraph create() {
        return new Impl(Limits.defaults());
    }

    public static ExportUsageReferenceGraph create(Limits limits) {
        return new Impl(limits);
    }

    public record Limits(int maxFiles, int maxHits, int maxReexportDepth) {
        public static Limits defaults() {
            return new Limits(2_000, 5_000, 25);
        }
    }

    private record Impl(Limits limits) implements ExportUsageReferenceGraph {
        @Override
        public ReferenceGraphResult findExportUsages(ProjectFile definingFile, String exportName, IAnalyzer analyzer)
                throws InterruptedException {
            return JsTsExportUsageReferenceGraph.findExportUsages(definingFile, exportName, analyzer, limits);
        }
    }

    public static ReferenceGraphResult findExportUsages(
            ProjectFile definingFile, String exportName, IAnalyzer analyzer, Limits limits)
            throws InterruptedException {
        if (!(analyzer instanceof JsTsAnalyzer jsTs)) {
            throw new IllegalArgumentException(
                    "Analyzer is not a JS/TS analyzer: " + analyzer.getClass().getName());
        }

        Set<String> externalFrontier = new LinkedHashSet<>();
        var resolution = resolveExport(definingFile, exportName, jsTs, limits, externalFrontier);
        Set<CodeUnit> targets = resolution.targets();
        Set<ProjectFile> frontier = new LinkedHashSet<>(resolution.frontier());

        if (targets.isEmpty()) {
            return new ReferenceGraphResult(Set.of(), Set.copyOf(frontier), Set.copyOf(externalFrontier));
        }

        // Seed candidate files by walking the reverse import graph starting from the defining module.
        Map<ProjectFile, Set<ProjectFile>> reverseReexports = buildReverseReexportIndex(jsTs);
        Set<ProjectFile> candidateFiles =
                collectReferencingFiles(definingFile, jsTs, reverseReexports, limits.maxFiles());

        Map<String, String> extendsEdges = buildClassExtendsIndex(jsTs);

        Set<ReferenceHit> hits = new LinkedHashSet<>();
        int filesProcessed = 0;

        for (ProjectFile file : candidateFiles) {
            if (filesProcessed >= limits.maxFiles()) break;
            filesProcessed++;

            ImportBinder binder = jsTs.importBinderOf(file);
            if (binder.bindings().isEmpty()) continue;

            Set<ReferenceCandidate> candidates = jsTs.exportUsageCandidatesOf(file, binder);
            if (candidates.isEmpty()) continue;

            for (ReferenceCandidate cand : candidates) {
                if (hits.size() >= limits.maxHits()) break;

                Optional<ResolvedExport> resolved =
                        resolveCandidate(cand, file, binder, jsTs, limits, frontier, externalFrontier);
                if (resolved.isEmpty()) continue;

                CodeUnit resolvedTarget = resolved.get().target();
                double confidence = resolved.get().confidence();

                for (CodeUnit target : targets) {
                    if (matchesTarget(target, resolvedTarget, extendsEdges)) {
                        hits.add(new ReferenceHit(
                                file, cand.range(), cand.enclosingUnit(), cand.kind(), target, confidence));
                        break;
                    }
                }
            }
        }

        return new ReferenceGraphResult(Set.copyOf(hits), Set.copyOf(frontier), Set.copyOf(externalFrontier));
    }

    private static boolean matchesTarget(
            CodeUnit queryTarget, CodeUnit resolvedTarget, Map<String, String> extendsEdges) {
        if (queryTarget.fqName().equals(resolvedTarget.fqName())) {
            return true;
        }

        if (queryTarget.kind() == CodeUnitType.FIELD
                && resolvedTarget.kind() == CodeUnitType.FIELD
                && queryTarget.source().equals(resolvedTarget.source())
                && queryTarget.identifier().equals(resolvedTarget.identifier())) {
            return true;
        }

        // JS/TS polymorphism (v1): class inheritance only, flow-insensitive.
        if (queryTarget.kind() == CodeUnitType.CLASS && resolvedTarget.kind() == CodeUnitType.CLASS) {
            String q = queryTarget.identifier();
            String current = resolvedTarget.identifier();
            while (true) {
                String parent = extendsEdges.get(current);
                if (parent == null) {
                    return false;
                }
                if (parent.equals(q)) {
                    return true;
                }
                current = parent;
            }
        }

        return false;
    }

    private record ResolvedExport(CodeUnit target, double confidence) {}

    private static Optional<ResolvedExport> resolveCandidate(
            ReferenceCandidate cand,
            ProjectFile file,
            ImportBinder binder,
            JsTsAnalyzer jsTs,
            Limits limits,
            Set<ProjectFile> frontier,
            Set<String> externalFrontier) {
        if (cand.qualifier() == null) {
            ImportBinder.ImportBinding binding = binder.bindings().get(cand.identifier());
            if (binding == null) return Optional.empty();

            JsTsAnalyzer.ResolutionOutcome imported = jsTs.resolveEsmModuleOutcome(file, binding.moduleSpecifier());
            if (imported.resolved().isEmpty()) {
                imported.externalFrontier().ifPresent(externalFrontier::add);
                return addFrontier(frontier, externalFrontier, binding.moduleSpecifier(), file, jsTs);
            }

            String importedName = binding.importedName();
            if (importedName == null) return Optional.empty();

            var resolution =
                    resolveExport(imported.resolved().orElseThrow(), importedName, jsTs, limits, externalFrontier);
            frontier.addAll(resolution.frontier());
            CodeUnit target = resolution.targets().stream().findFirst().orElse(null);
            if (target == null) return Optional.empty();

            double confidence = binding.kind() == ImportBinder.ImportKind.NAMED ? 1.0 : 0.9;
            return Optional.of(new ResolvedExport(target, confidence));
        }

        ImportBinder.ImportBinding binding = binder.bindings().get(cand.qualifier());
        if (binding == null || binding.kind() != ImportBinder.ImportKind.NAMESPACE) {
            return Optional.empty();
        }

        JsTsAnalyzer.ResolutionOutcome imported = jsTs.resolveEsmModuleOutcome(file, binding.moduleSpecifier());
        if (imported.resolved().isEmpty()) {
            imported.externalFrontier().ifPresent(externalFrontier::add);
            return addFrontier(frontier, externalFrontier, binding.moduleSpecifier(), file, jsTs);
        }

        var resolution =
                resolveExport(imported.resolved().orElseThrow(), cand.identifier(), jsTs, limits, externalFrontier);
        frontier.addAll(resolution.frontier());
        CodeUnit target = resolution.targets().stream().findFirst().orElse(null);
        if (target == null) return Optional.empty();
        return Optional.of(new ResolvedExport(target, 0.9));
    }

    private static Optional<ResolvedExport> addFrontier(
            Set<ProjectFile> frontier,
            Set<String> externalFrontier,
            String moduleSpecifier,
            ProjectFile importingFile,
            JsTsAnalyzer jsTs) {
        JsTsAnalyzer.ResolutionOutcome outcome = jsTs.resolveEsmModuleOutcome(importingFile, moduleSpecifier);
        outcome.resolved().ifPresent(frontier::add);
        outcome.externalFrontier().ifPresent(externalFrontier::add);
        return Optional.empty();
    }

    private record ExportResolution(Set<CodeUnit> targets, Set<ProjectFile> frontier) {}

    private static ExportResolution resolveExport(
            ProjectFile file, String exportName, JsTsAnalyzer jsTs, Limits limits, Set<String> externalFrontier) {
        var frontier = new LinkedHashSet<ProjectFile>();
        var targets = new LinkedHashSet<CodeUnit>();

        var queue = new ArrayDeque<Map.Entry<ProjectFile, String>>();
        queue.add(Map.entry(file, exportName));

        var visited = new HashSet<String>();
        int depth = 0;

        while (!queue.isEmpty() && depth < limits.maxReexportDepth()) {
            depth++;
            var item = queue.removeFirst();
            ProjectFile currentFile = item.getKey();
            String name = item.getValue();

            String visitKey = currentFile + "::" + name;
            if (!visited.add(visitKey)) continue;

            ExportIndex index = jsTs.exportIndexOf(currentFile);
            ExportIndex.ExportEntry entry = index.exportsByName().get(name);

            if (entry instanceof ExportIndex.LocalExport local) {
                targets.addAll(resolveLocalExport(currentFile, local.localName(), jsTs));
                continue;
            }

            if (entry instanceof ExportIndex.DefaultExport def) {
                String localName = def.localName();
                if (localName == null) {
                    targets.add(syntheticModuleField(currentFile, "default"));
                    continue;
                }
                targets.addAll(resolveLocalExport(currentFile, localName, jsTs));
                continue;
            }

            if (entry instanceof ExportIndex.ReexportedNamed reexp) {
                JsTsAnalyzer.ResolutionOutcome resolved =
                        jsTs.resolveEsmModuleOutcome(currentFile, reexp.moduleSpecifier());
                if (resolved.resolved().isEmpty()) {
                    resolved.externalFrontier().ifPresent(externalFrontier::add);
                    continue;
                }
                queue.add(Map.entry(resolved.resolved().orElseThrow(), reexp.importedName()));
                continue;
            }

            // Not an explicit export: try star re-exports.
            for (ExportIndex.ReexportStar star : index.reexportStars()) {
                JsTsAnalyzer.ResolutionOutcome resolved =
                        jsTs.resolveEsmModuleOutcome(currentFile, star.moduleSpecifier());
                if (resolved.resolved().isEmpty()) {
                    resolved.externalFrontier().ifPresent(externalFrontier::add);
                    continue;
                }
                queue.add(Map.entry(resolved.resolved().orElseThrow(), name));
            }
        }

        return new ExportResolution(Set.copyOf(targets), Set.copyOf(frontier));
    }

    private static Set<CodeUnit> resolveLocalExport(ProjectFile file, String localName, IAnalyzer analyzer) {
        var matches = new LinkedHashSet<CodeUnit>();
        for (CodeUnit cu : analyzer.getDefinitions(localName)) {
            if (!cu.source().equals(file)) continue;
            if (cu.identifier().equals(localName) || cu.shortName().equals(localName)) {
                matches.add(cu);
            }
        }
        if (matches.isEmpty()) {
            // JS/TS sometimes does not emit CodeUnits for certain top-level export forms yet (e.g., exported const).
            // Create a stable synthetic FIELD CodeUnit so exported-symbol orchestration still works.
            matches.add(syntheticModuleField(file, localName));
        }
        return Set.copyOf(matches);
    }

    private static CodeUnit syntheticModuleField(ProjectFile file, String name) {
        return CodeUnit.field(file, "", "_module_." + name).withSynthetic(true);
    }

    private static Set<ProjectFile> collectReferencingFiles(
            ProjectFile start, JsTsAnalyzer jsTs, Map<ProjectFile, Set<ProjectFile>> reverseReexports, int maxFiles)
            throws InterruptedException {
        var providerOpt = jsTs.as(ImportAnalysisProvider.class);
        if (providerOpt.isEmpty()) {
            return reverseReexports.getOrDefault(start, Set.of());
        }

        var provider = providerOpt.get();
        ensureImportReverseIndexPopulated(jsTs, provider);
        var seen = new LinkedHashSet<ProjectFile>();
        var queue = new ArrayDeque<ProjectFile>();
        queue.add(start);
        seen.add(start);

        while (!queue.isEmpty() && seen.size() < maxFiles) {
            ProjectFile current = queue.removeFirst();
            var neighbors = new LinkedHashSet<ProjectFile>();
            neighbors.addAll(provider.referencingFilesOf(current));
            neighbors.addAll(reverseReexports.getOrDefault(current, Set.of()));

            for (ProjectFile ref : neighbors) {
                if (seen.add(ref)) {
                    queue.addLast(ref);
                    if (seen.size() >= maxFiles) break;
                }
            }
        }

        return Set.copyOf(seen);
    }

    private static Map<ProjectFile, Set<ProjectFile>> buildReverseReexportIndex(JsTsAnalyzer jsTs) {
        var reverse = new HashMap<ProjectFile, Set<ProjectFile>>();
        for (ProjectFile file : jsTs.getAnalyzedFiles()) {
            String ext = file.extension();
            if (!"js".equals(ext) && !"jsx".equals(ext) && !"ts".equals(ext) && !"tsx".equals(ext)) continue;

            ExportIndex idx = jsTs.exportIndexOf(file);
            for (ExportIndex.ExportEntry entry : idx.exportsByName().values()) {
                if (entry instanceof ExportIndex.ReexportedNamed named) {
                    jsTs.resolveEsmModule(file, named.moduleSpecifier())
                            .ifPresent(target -> reverse.computeIfAbsent(target, k -> new LinkedHashSet<>())
                                    .add(file));
                }
            }
            for (ExportIndex.ReexportStar star : idx.reexportStars()) {
                jsTs.resolveEsmModule(file, star.moduleSpecifier())
                        .ifPresent(target -> reverse.computeIfAbsent(target, k -> new LinkedHashSet<>())
                                .add(file));
            }
        }
        return Map.copyOf(reverse);
    }

    private static void ensureImportReverseIndexPopulated(JsTsAnalyzer jsTs, ImportAnalysisProvider provider)
            throws InterruptedException {
        // The bidirectional imports cache populates reverse mappings lazily; make sure it's available for
        // referencingFilesOf(...) by priming the forward lookups.
        for (ProjectFile file : jsTs.getAnalyzedFiles()) {
            String ext = file.extension();
            if (!"js".equals(ext) && !"jsx".equals(ext) && !"ts".equals(ext) && !"tsx".equals(ext)) continue;
            provider.importedCodeUnitsOf(file);
        }
    }

    private static Map<String, String> buildClassExtendsIndex(JsTsAnalyzer jsTs) {
        var edges = new HashMap<String, String>();
        for (ProjectFile file : jsTs.getAnalyzedFiles()) {
            String ext = file.extension();
            if (!"js".equals(ext) && !"jsx".equals(ext) && !"ts".equals(ext) && !"tsx".equals(ext)) continue;
            ExportIndex idx = jsTs.exportIndexOf(file);
            for (ExportIndex.ClassExtendsEdge e : idx.classExtendsEdges()) {
                edges.putIfAbsent(e.childClassName(), e.parentClassName());
            }
        }
        return Map.copyOf(edges);
    }
}
