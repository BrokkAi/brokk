package ai.brokk.analyzer.usages;

import static java.util.Objects.requireNonNull;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ImportAnalysisProvider;
import ai.brokk.analyzer.JsTsAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * JS/TS v1: flow-insensitive exported-symbol usages.
 *
 * <p>This is intentionally built on top of language-agnostic IR types ({@link ReferenceCandidate}, {@link ReferenceHit})
 * so that other languages can plug into the same orchestration later.
 */
public final class JsTsExportUsageReferenceGraph {

    private JsTsExportUsageReferenceGraph() {}

    private static boolean isJsTs(ProjectFile file) {
        Language lang = Languages.fromExtension(file.extension());
        return lang.contains(Languages.JAVASCRIPT) || lang.contains(Languages.TYPESCRIPT);
    }

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
        return findExportUsages(definingFile, exportName, null, analyzer, limits, null);
    }

    public static ReferenceGraphResult findExportUsages(
            ProjectFile definingFile,
            String exportName,
            IAnalyzer analyzer,
            Limits limits,
            @Nullable Set<ProjectFile> candidateFiles)
            throws InterruptedException {
        return findExportUsages(definingFile, exportName, null, analyzer, limits, candidateFiles);
    }

    public static ReferenceGraphResult findExportUsages(
            ProjectFile definingFile,
            String exportName,
            @Nullable CodeUnit queryTarget,
            IAnalyzer analyzer,
            Limits limits,
            @Nullable Set<ProjectFile> candidateFiles)
            throws InterruptedException {
        if (!(analyzer instanceof JsTsAnalyzer jsTs)) {
            throw new IllegalArgumentException(
                    "Analyzer is not a JS/TS analyzer: " + analyzer.getClass().getName());
        }

        Set<String> externalFrontier = new LinkedHashSet<>();
        var resolution = resolveExport(definingFile, exportName, jsTs, limits, externalFrontier);
        Set<CodeUnit> targets = queryTarget != null ? Set.of(queryTarget) : resolution.targets();
        Set<ProjectFile> frontier = new LinkedHashSet<>(resolution.frontier());

        if (targets.isEmpty()) {
            return new ReferenceGraphResult(Set.of(), Set.copyOf(frontier), Set.copyOf(externalFrontier));
        }

        Set<ProjectFile> filesToAnalyze;
        if (candidateFiles != null) {
            filesToAnalyze = Set.copyOf(candidateFiles);
        } else {
            // Seed candidate files by walking the reverse import graph starting from the defining module.
            Map<ProjectFile, Set<ProjectFile>> reverseReexports = buildReverseReexportIndex(jsTs);
            filesToAnalyze = collectReferencingFiles(definingFile, jsTs, reverseReexports, limits.maxFiles());
        }

        Map<String, Set<String>> heritageEdges = buildHeritageIndex(jsTs);

        Set<ReferenceHit> hits = new LinkedHashSet<>();
        int filesProcessed = 0;

        for (ProjectFile file : filesToAnalyze) {
            if (filesProcessed >= limits.maxFiles()) break;
            filesProcessed++;

            ImportBinder binder = jsTs.importBinderOf(file);
            if (binder.bindings().isEmpty()) continue;

            Set<ReferenceCandidate> candidates = jsTs.exportUsageCandidatesOf(file, binder);
            Set<ResolvedReceiverCandidate> receiverCandidates = jsTs.resolvedReceiverCandidatesOf(file, binder);
            if (candidates.isEmpty() && receiverCandidates.isEmpty()) continue;

            for (ReferenceCandidate cand : candidates) {
                if (hits.size() >= limits.maxHits()) break;

                Optional<ResolvedExport> resolved =
                        resolveCandidate(cand, file, binder, jsTs, limits, frontier, externalFrontier);
                if (resolved.isEmpty()) continue;

                CodeUnit resolvedTarget = resolved.get().target();
                double confidence = resolved.get().confidence();

                for (CodeUnit target : targets) {
                    if (matchesTarget(target, resolvedTarget, heritageEdges)) {
                        hits.add(new ReferenceHit(
                                file, cand.range(), cand.enclosingUnit(), cand.kind(), target, confidence));
                        break;
                    }
                }
            }

            for (ResolvedReceiverCandidate cand : receiverCandidates) {
                if (hits.size() >= limits.maxHits()) break;

                Optional<ResolvedExport> resolved =
                        resolveReceiverCandidate(cand, file, jsTs, limits, frontier, externalFrontier);
                if (resolved.isEmpty()) continue;

                CodeUnit resolvedTarget = resolved.get().target();
                double confidence = resolved.get().confidence();

                for (CodeUnit target : targets) {
                    if (matchesTarget(target, resolvedTarget, heritageEdges)) {
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
            CodeUnit queryTarget, CodeUnit resolvedTarget, Map<String, Set<String>> heritageEdges) {
        if (queryTarget.fqName().equals(resolvedTarget.fqName())) {
            return true;
        }

        if ((queryTarget.kind() == CodeUnitType.FIELD || queryTarget.kind() == CodeUnitType.FUNCTION)
                && (resolvedTarget.kind() == CodeUnitType.FIELD || resolvedTarget.kind() == CodeUnitType.FUNCTION)
                && queryTarget.source().equals(resolvedTarget.source())
                && normalizedMemberName(queryTarget).equals(normalizedMemberName(resolvedTarget))
                && ownerNameOf(queryTarget).equals(ownerNameOf(resolvedTarget))) {
            return true;
        }

        // JS/TS polymorphism (v1): class inheritance only, flow-insensitive.
        if (queryTarget.kind() == CodeUnitType.CLASS && resolvedTarget.kind() == CodeUnitType.CLASS) {
            String q = queryTarget.identifier();
            var queue = new ArrayDeque<String>();
            var visited = new HashSet<String>();
            queue.add(resolvedTarget.identifier());
            while (!queue.isEmpty()) {
                String current = queue.removeFirst();
                if (!visited.add(current)) {
                    continue;
                }
                Set<String> parents = heritageEdges.get(current);
                if (parents == null || parents.isEmpty()) {
                    continue;
                }
                if (parents.contains(q)) {
                    return true;
                }
                parents.forEach(queue::addLast);
            }
            return false;
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
        if (cand.ownerIdentifier() != null) {
            return resolveNamespaceMemberCandidate(cand, file, binder, jsTs, limits, frontier, externalFrontier);
        }

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
        if (binding == null) {
            return Optional.empty();
        }

        if (binding.kind() != ImportBinder.ImportKind.NAMESPACE) {
            return resolveClassMemberCandidate(cand, file, binding, jsTs, limits, frontier, externalFrontier);
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

    private static Optional<ResolvedExport> resolveClassMemberCandidate(
            ReferenceCandidate cand,
            ProjectFile file,
            ImportBinder.ImportBinding binding,
            JsTsAnalyzer jsTs,
            Limits limits,
            Set<ProjectFile> frontier,
            Set<String> externalFrontier) {
        JsTsAnalyzer.ResolutionOutcome imported = jsTs.resolveEsmModuleOutcome(file, binding.moduleSpecifier());
        if (imported.resolved().isEmpty()) {
            imported.externalFrontier().ifPresent(externalFrontier::add);
            return addFrontier(frontier, externalFrontier, binding.moduleSpecifier(), file, jsTs);
        }

        String importedName = binding.importedName();
        if (importedName == null) {
            return Optional.empty();
        }

        var ownerResolution =
                resolveExport(imported.resolved().orElseThrow(), importedName, jsTs, limits, externalFrontier);
        frontier.addAll(ownerResolution.frontier());
        CodeUnit ownerClass = ownerResolution.targets().stream()
                .filter(cu -> cu.kind() == CodeUnitType.CLASS)
                .findFirst()
                .orElse(null);
        if (ownerClass == null) {
            return Optional.empty();
        }

        CodeUnit member = resolveClassMember(ownerClass, cand.identifier(), cand.instanceReceiver(), jsTs);
        if (member == null) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedExport(member, cand.instanceReceiver() ? 0.95 : 1.0));
    }

    private static Optional<ResolvedExport> resolveNamespaceMemberCandidate(
            ReferenceCandidate cand,
            ProjectFile file,
            ImportBinder binder,
            JsTsAnalyzer jsTs,
            Limits limits,
            Set<ProjectFile> frontier,
            Set<String> externalFrontier) {
        ImportBinder.ImportBinding binding = binder.bindings().get(cand.qualifier());
        if (binding == null || binding.kind() != ImportBinder.ImportKind.NAMESPACE) {
            return Optional.empty();
        }
        String ownerIdentifier = requireNonNull(cand.ownerIdentifier());

        JsTsAnalyzer.ResolutionOutcome imported = jsTs.resolveEsmModuleOutcome(file, binding.moduleSpecifier());
        if (imported.resolved().isEmpty()) {
            imported.externalFrontier().ifPresent(externalFrontier::add);
            return addFrontier(frontier, externalFrontier, binding.moduleSpecifier(), file, jsTs);
        }

        var ownerResolution =
                resolveExport(imported.resolved().orElseThrow(), ownerIdentifier, jsTs, limits, externalFrontier);
        frontier.addAll(ownerResolution.frontier());
        CodeUnit ownerClass = ownerResolution.targets().stream()
                .filter(cu -> cu.kind() == CodeUnitType.CLASS)
                .findFirst()
                .orElse(null);
        if (ownerClass == null) {
            return Optional.empty();
        }

        CodeUnit member = resolveClassMember(ownerClass, cand.identifier(), false, jsTs);
        if (member == null) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedExport(member, 1.0));
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

    private static Optional<ResolvedExport> resolveReceiverCandidate(
            ResolvedReceiverCandidate cand,
            ProjectFile file,
            JsTsAnalyzer jsTs,
            Limits limits,
            Set<ProjectFile> frontier,
            Set<String> externalFrontier) {
        JsTsAnalyzer.ResolutionOutcome imported =
                jsTs.resolveEsmModuleOutcome(file, cand.receiverTarget().moduleSpecifier());
        if (imported.resolved().isEmpty()) {
            imported.externalFrontier().ifPresent(externalFrontier::add);
            return addFrontier(frontier, externalFrontier, cand.receiverTarget().moduleSpecifier(), file, jsTs);
        }

        var ownerResolution = resolveExport(
                imported.resolved().orElseThrow(),
                cand.receiverTarget().exportedName(),
                jsTs,
                limits,
                externalFrontier);
        frontier.addAll(ownerResolution.frontier());
        CodeUnit ownerClass = ownerResolution.targets().stream()
                .filter(cu -> cu.kind() == CodeUnitType.CLASS)
                .findFirst()
                .orElse(null);
        if (ownerClass == null) {
            return Optional.empty();
        }

        CodeUnit member = resolveClassMember(
                ownerClass, cand.identifier(), cand.receiverTarget().instanceReceiver(), jsTs);
        if (member == null) {
            return Optional.empty();
        }

        return Optional.of(new ResolvedExport(member, cand.confidence()));
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
            if (cu.identifier().equals(localName)
                    || cu.shortName().equals(localName)
                    || ownerNameOf(cu).equals(localName)) {
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

    private static @Nullable CodeUnit resolveClassMember(
            CodeUnit ownerClass, String memberName, boolean instanceReceiver, JsTsAnalyzer jsTs) {
        ExportIndex idx = jsTs.exportIndexOf(ownerClass.source());
        boolean memberDeclared = idx.classMembers().stream()
                .anyMatch(member -> member.ownerClassName().equals(ownerClass.identifier())
                        && member.memberName().equals(memberName)
                        && (instanceReceiver ? !member.staticMember() : true));
        if (!memberDeclared) {
            return null;
        }

        for (CodeUnit cu : jsTs.getAllDeclarations()) {
            if (!cu.source().equals(ownerClass.source())) {
                continue;
            }
            if (!normalizedMemberName(cu).equals(memberName)) {
                continue;
            }
            if (!ownerNameOf(cu).equals(ownerClass.identifier())) {
                continue;
            }
            if (instanceReceiver && isStaticMember(cu)) {
                continue;
            }
            return cu;
        }

        return CodeUnit.field(ownerClass.source(), ownerClass.packageName(), ownerClass.identifier() + "." + memberName)
                .withSynthetic(true);
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
            if (!isJsTs(file)) continue;

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
            if (!isJsTs(file)) continue;
            provider.importedCodeUnitsOf(file);
        }
    }

    private static Map<String, Set<String>> buildHeritageIndex(JsTsAnalyzer jsTs) {
        var edges = new HashMap<String, Set<String>>();
        for (ProjectFile file : jsTs.getAnalyzedFiles()) {
            if (!isJsTs(file)) continue;
            ExportIndex idx = jsTs.exportIndexOf(file);
            for (ExportIndex.HeritageEdge e : idx.heritageEdges()) {
                edges.computeIfAbsent(e.childName(), ignored -> new LinkedHashSet<>())
                        .add(e.parentName());
            }
        }
        return Map.copyOf(edges);
    }

    private static String normalizedMemberName(CodeUnit codeUnit) {
        return stripMemberSuffix(codeUnit.identifier());
    }

    private static String ownerNameOf(CodeUnit codeUnit) {
        String shortName = codeUnit.shortName();
        int lastDot = shortName.lastIndexOf('.');
        if (lastDot <= 0) {
            return "";
        }
        return shortName.substring(0, lastDot);
    }

    private static boolean isStaticMember(CodeUnit codeUnit) {
        return codeUnit.fqName().contains("$static");
    }

    private static String stripMemberSuffix(String name) {
        int marker = name.indexOf('$');
        return marker >= 0 ? name.substring(0, marker) : name;
    }
}
