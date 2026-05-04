package ai.brokk.analyzer.usages;

import static java.util.Objects.requireNonNull;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.JsTsAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.util.PathNormalizer;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JS/TS v1: flow-insensitive exported-symbol usages.
 *
 * <p>This is intentionally built on top of language-agnostic IR types ({@link ReferenceCandidate}, {@link ReferenceHit})
 * so that other languages can plug into the same orchestration later.
 */
public final class JsTsExportUsageReferenceGraph {
    private static final Logger log = LoggerFactory.getLogger(JsTsExportUsageReferenceGraph.class);

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
        var adapter = new JsTsExportUsageGraphAdapter(jsTs);
        return findExportUsages(definingFile, exportName, queryTarget, adapter, limits, candidateFiles);
    }

    static ReferenceGraphResult findExportUsages(
            ProjectFile definingFile,
            String exportName,
            @Nullable CodeUnit queryTarget,
            ExportUsageGraphLanguageAdapter adapter,
            Limits limits,
            @Nullable Set<ProjectFile> candidateFiles)
            throws InterruptedException {
        Set<String> externalFrontier = new LinkedHashSet<>();
        var resolution = resolveExport(definingFile, exportName, adapter, limits, externalFrontier);
        Set<CodeUnit> targets = queryTarget != null ? Set.of(queryTarget) : resolution.targets();
        Set<ProjectFile> frontier = new LinkedHashSet<>(resolution.frontier());

        log.debug(
                "JS/TS reference graph resolving {}:{} -> {} targets, {} frontier files, queryTarget={}",
                definingFile,
                exportName,
                targets.size(),
                frontier.size(),
                queryTarget != null ? queryTarget.fqName() : "<none>");

        if (targets.isEmpty()) {
            log.debug("JS/TS reference graph found no targets for {}:{}", definingFile, exportName);
            return new ReferenceGraphResult(Set.of(), Set.copyOf(frontier), Set.copyOf(externalFrontier));
        }

        Map<ProjectFile, Set<ProjectFile>> reverseReexports = adapter.reverseReexportIndex();
        Set<ProjectFile> filesToAnalyze = candidateFiles != null && !candidateFiles.isEmpty()
                ? Set.copyOf(candidateFiles)
                : collectReferencingFiles(definingFile, adapter, reverseReexports, limits.maxFiles());
        var expandedFilesToAnalyze = new LinkedHashSet<>(filesToAnalyze);
        expandedFilesToAnalyze.add(definingFile);
        filesToAnalyze = Set.copyOf(expandedFilesToAnalyze);
        boolean shouldResolveReceiverCandidates =
                targets.stream().anyMatch(JsTsExportUsageReferenceGraph::isMemberTarget);

        log.debug(
                "JS/TS reference graph analyzing {} files for {}:{} (receiverCandidates={})",
                filesToAnalyze.size(),
                definingFile,
                exportName,
                shouldResolveReceiverCandidates);

        Map<String, Set<String>> heritageEdges =
                targets.stream().allMatch(target -> target.kind() != CodeUnitType.CLASS && !isMemberTarget(target))
                        ? Map.of()
                        : adapter.heritageIndex();

        Set<ReferenceHit> hits = new LinkedHashSet<>();
        int filesProcessed = 0;

        for (ProjectFile file : filesToAnalyze) {
            if (filesProcessed >= limits.maxFiles()) break;
            filesProcessed++;

            ImportBinder binder = adapter.importBinderOf(file);
            Set<ReferenceCandidate> candidates = adapter.usageCandidatesOf(file, binder);
            Set<ResolvedReceiverCandidate> receiverCandidates =
                    shouldResolveReceiverCandidates ? adapter.resolvedReceiverCandidatesOf(file, binder) : Set.of();
            if (candidates.isEmpty() && receiverCandidates.isEmpty()) continue;

            for (ReferenceCandidate cand : candidates) {
                if (hits.size() >= limits.maxHits()) break;

                Optional<ResolvedExport> resolved =
                        resolveCandidate(cand, file, binder, adapter, limits, frontier, externalFrontier);
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
                        resolveReceiverCandidate(cand, file, adapter, limits, frontier, externalFrontier);
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

        log.debug("JS/TS reference graph produced {} hits for {}:{}", hits.size(), definingFile, exportName);
        return new ReferenceGraphResult(Set.copyOf(hits), Set.copyOf(frontier), Set.copyOf(externalFrontier));
    }

    private static boolean matchesTarget(
            CodeUnit queryTarget, CodeUnit resolvedTarget, Map<String, Set<String>> heritageEdges) {
        if (queryTarget.source().equals(resolvedTarget.source())
                && queryTarget.fqName().equals(resolvedTarget.fqName())) {
            return true;
        }

        if ((queryTarget.kind() == CodeUnitType.FIELD || queryTarget.kind() == CodeUnitType.FUNCTION)
                && (resolvedTarget.kind() == CodeUnitType.FIELD || resolvedTarget.kind() == CodeUnitType.FUNCTION)
                && normalizedMemberName(queryTarget).equals(normalizedMemberName(resolvedTarget))) {
            String queryOwner = qualifiedOwnerKey(queryTarget);
            String resolvedOwner = qualifiedOwnerKey(resolvedTarget);
            if (!queryOwner.isEmpty() && queryOwner.equals(resolvedOwner)) {
                return true;
            }
            return ownerMatchesHeritage(queryOwner, resolvedOwner, heritageEdges);
        }

        // JS/TS polymorphism (v1): class inheritance only, flow-insensitive.
        if (queryTarget.kind() == CodeUnitType.CLASS && resolvedTarget.kind() == CodeUnitType.CLASS) {
            String q = qualifiedClassKey(queryTarget);
            var queue = new ArrayDeque<String>();
            var visited = new HashSet<String>();
            queue.add(qualifiedClassKey(resolvedTarget));
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
            ExportUsageGraphLanguageAdapter adapter,
            Limits limits,
            Set<ProjectFile> frontier,
            Set<String> externalFrontier) {
        if (cand.ownerIdentifier() != null) {
            if (cand.qualifier() == null) {
                return resolveLocalOwnerMemberCandidate(cand, file, adapter);
            }
            return resolveNamespaceMemberCandidate(cand, file, binder, adapter, limits, frontier, externalFrontier);
        }

        if (cand.qualifier() == null) {
            ImportBinder.ImportBinding binding = binder.bindings().get(cand.identifier());
            if (binding == null) return Optional.empty();

            ExportUsageGraphLanguageAdapter.ResolutionOutcome imported =
                    adapter.resolveModule(file, binding.moduleSpecifier());
            if (imported.resolved().isEmpty()) {
                imported.externalFrontier().ifPresent(externalFrontier::add);
                return addFrontier(frontier, externalFrontier, binding.moduleSpecifier(), file, adapter);
            }

            String importedName = binding.importedName();
            if (importedName == null) return Optional.empty();

            var resolution =
                    resolveExport(imported.resolved().orElseThrow(), importedName, adapter, limits, externalFrontier);
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
            return resolveClassMemberCandidate(cand, file, binding, adapter, limits, frontier, externalFrontier);
        }

        ExportUsageGraphLanguageAdapter.ResolutionOutcome imported =
                adapter.resolveModule(file, binding.moduleSpecifier());
        if (imported.resolved().isEmpty()) {
            imported.externalFrontier().ifPresent(externalFrontier::add);
            return addFrontier(frontier, externalFrontier, binding.moduleSpecifier(), file, adapter);
        }

        var resolution =
                resolveExport(imported.resolved().orElseThrow(), cand.identifier(), adapter, limits, externalFrontier);
        frontier.addAll(resolution.frontier());
        CodeUnit target = resolution.targets().stream().findFirst().orElse(null);
        if (target == null) return Optional.empty();
        return Optional.of(new ResolvedExport(target, 0.9));
    }

    private static Optional<ResolvedExport> resolveClassMemberCandidate(
            ReferenceCandidate cand,
            ProjectFile file,
            ImportBinder.ImportBinding binding,
            ExportUsageGraphLanguageAdapter adapter,
            Limits limits,
            Set<ProjectFile> frontier,
            Set<String> externalFrontier) {
        ExportUsageGraphLanguageAdapter.ResolutionOutcome imported =
                adapter.resolveModule(file, binding.moduleSpecifier());
        if (imported.resolved().isEmpty()) {
            imported.externalFrontier().ifPresent(externalFrontier::add);
            return addFrontier(frontier, externalFrontier, binding.moduleSpecifier(), file, adapter);
        }

        String importedName = binding.importedName();
        if (importedName == null) {
            return Optional.empty();
        }

        var ownerResolution =
                resolveExport(imported.resolved().orElseThrow(), importedName, adapter, limits, externalFrontier);
        frontier.addAll(ownerResolution.frontier());
        CodeUnit ownerClass = ownerResolution.targets().stream()
                .filter(cu -> cu.kind() == CodeUnitType.CLASS)
                .findFirst()
                .orElse(null);
        if (ownerClass == null) {
            return Optional.empty();
        }

        CodeUnit member = resolveClassMember(ownerClass, cand.identifier(), cand.instanceReceiver(), adapter);
        if (member == null) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedExport(member, cand.instanceReceiver() ? 0.95 : 1.0));
    }

    private static Optional<ResolvedExport> resolveNamespaceMemberCandidate(
            ReferenceCandidate cand,
            ProjectFile file,
            ImportBinder binder,
            ExportUsageGraphLanguageAdapter adapter,
            Limits limits,
            Set<ProjectFile> frontier,
            Set<String> externalFrontier) {
        ImportBinder.ImportBinding binding = binder.bindings().get(cand.qualifier());
        if (binding == null || binding.kind() != ImportBinder.ImportKind.NAMESPACE) {
            return Optional.empty();
        }
        String ownerIdentifier = requireNonNull(cand.ownerIdentifier());

        ExportUsageGraphLanguageAdapter.ResolutionOutcome imported =
                adapter.resolveModule(file, binding.moduleSpecifier());
        if (imported.resolved().isEmpty()) {
            imported.externalFrontier().ifPresent(externalFrontier::add);
            return addFrontier(frontier, externalFrontier, binding.moduleSpecifier(), file, adapter);
        }

        var ownerResolution =
                resolveExport(imported.resolved().orElseThrow(), ownerIdentifier, adapter, limits, externalFrontier);
        frontier.addAll(ownerResolution.frontier());
        CodeUnit ownerClass = ownerResolution.targets().stream()
                .filter(cu -> cu.kind() == CodeUnitType.CLASS)
                .findFirst()
                .orElse(null);
        if (ownerClass == null) {
            return Optional.empty();
        }

        CodeUnit member = resolveClassMember(ownerClass, cand.identifier(), false, adapter);
        if (member == null) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedExport(member, 1.0));
    }

    private static Optional<ResolvedExport> resolveLocalOwnerMemberCandidate(
            ReferenceCandidate cand, ProjectFile file, ExportUsageGraphLanguageAdapter adapter) {
        String ownerIdentifier = requireNonNull(cand.ownerIdentifier());
        CodeUnit member =
                resolveClassMember(file, ownerIdentifier, cand.identifier(), cand.instanceReceiver(), adapter);
        if (member == null) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedExport(member, 0.98));
    }

    private static Optional<ResolvedExport> addFrontier(
            Set<ProjectFile> frontier,
            Set<String> externalFrontier,
            String moduleSpecifier,
            ProjectFile importingFile,
            ExportUsageGraphLanguageAdapter adapter) {
        ExportUsageGraphLanguageAdapter.ResolutionOutcome outcome =
                adapter.resolveModule(importingFile, moduleSpecifier);
        outcome.resolved().ifPresent(frontier::add);
        outcome.externalFrontier().ifPresent(externalFrontier::add);
        return Optional.empty();
    }

    private static Optional<ResolvedExport> resolveReceiverCandidate(
            ResolvedReceiverCandidate cand,
            ProjectFile file,
            ExportUsageGraphLanguageAdapter adapter,
            Limits limits,
            Set<ProjectFile> frontier,
            Set<String> externalFrontier) {
        ProjectFile ownerFile = cand.receiverTarget().localFile();
        if (ownerFile == null) {
            String moduleSpecifier = requireNonNull(cand.receiverTarget().moduleSpecifier());
            ExportUsageGraphLanguageAdapter.ResolutionOutcome imported = adapter.resolveModule(file, moduleSpecifier);
            if (imported.resolved().isEmpty()) {
                imported.externalFrontier().ifPresent(externalFrontier::add);
                return addFrontier(frontier, externalFrontier, moduleSpecifier, file, adapter);
            }
            ownerFile = imported.resolved().orElseThrow();
        }

        var ownerResolution =
                resolveExport(ownerFile, cand.receiverTarget().exportedName(), adapter, limits, externalFrontier);
        frontier.addAll(ownerResolution.frontier());
        CodeUnit ownerClass = ownerResolution.targets().stream()
                .filter(cu -> cu.kind() == CodeUnitType.CLASS)
                .findFirst()
                .orElse(null);
        if (ownerClass == null) {
            return Optional.empty();
        }

        CodeUnit member = resolveClassMember(
                ownerClass, cand.identifier(), cand.receiverTarget().instanceReceiver(), adapter);
        if (member == null) {
            return Optional.empty();
        }

        return Optional.of(new ResolvedExport(member, cand.confidence()));
    }

    private record ExportResolution(Set<CodeUnit> targets, Set<ProjectFile> frontier) {}

    private static ExportResolution resolveExport(
            ProjectFile file,
            String exportName,
            ExportUsageGraphLanguageAdapter adapter,
            Limits limits,
            Set<String> externalFrontier) {
        ExportResolutionData cached = adapter.cachedExportResolution(
                file,
                exportName,
                limits.maxReexportDepth(),
                () -> computeExportResolution(file, exportName, adapter, limits));
        externalFrontier.addAll(cached.externalFrontier());
        return new ExportResolution(cached.targets(), cached.frontier());
    }

    private static ExportResolutionData computeExportResolution(
            ProjectFile file, String exportName, ExportUsageGraphLanguageAdapter adapter, Limits limits) {
        var frontier = new LinkedHashSet<ProjectFile>();
        var targets = new LinkedHashSet<CodeUnit>();
        var externalFrontier = new LinkedHashSet<String>();

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

            ExportIndex index = adapter.exportIndexOf(currentFile);
            ExportIndex.ExportEntry entry = index.exportsByName().get(name);

            if (entry instanceof ExportIndex.LocalExport local) {
                if (tryQueueImportedLocalExport(
                        currentFile, local.localName(), adapter, frontier, externalFrontier, queue)) {
                    continue;
                }
                targets.addAll(resolveLocalExport(currentFile, local.localName(), adapter));
                continue;
            }

            if (entry instanceof ExportIndex.DefaultExport def) {
                String localName = def.localName();
                if (localName == null) {
                    targets.add(syntheticModuleField(currentFile, "default"));
                    continue;
                }
                if (tryQueueImportedLocalExport(currentFile, localName, adapter, frontier, externalFrontier, queue)) {
                    continue;
                }
                targets.addAll(resolveLocalExport(currentFile, localName, adapter));
                continue;
            }

            if (entry instanceof ExportIndex.ReexportedNamed reexp) {
                ExportUsageGraphLanguageAdapter.ResolutionOutcome resolved =
                        adapter.resolveModule(currentFile, reexp.moduleSpecifier());
                if (resolved.resolved().isEmpty()) {
                    resolved.externalFrontier().ifPresent(externalFrontier::add);
                    continue;
                }
                queue.add(Map.entry(resolved.resolved().orElseThrow(), reexp.importedName()));
                continue;
            }

            // Not an explicit export: try star re-exports.
            for (ExportIndex.ReexportStar star : index.reexportStars()) {
                ExportUsageGraphLanguageAdapter.ResolutionOutcome resolved =
                        adapter.resolveModule(currentFile, star.moduleSpecifier());
                if (resolved.resolved().isEmpty()) {
                    resolved.externalFrontier().ifPresent(externalFrontier::add);
                    continue;
                }
                queue.add(Map.entry(resolved.resolved().orElseThrow(), name));
            }
        }

        return new ExportResolutionData(Set.copyOf(targets), Set.copyOf(frontier), Set.copyOf(externalFrontier));
    }

    private static boolean tryQueueImportedLocalExport(
            ProjectFile currentFile,
            String localName,
            ExportUsageGraphLanguageAdapter adapter,
            Set<ProjectFile> frontier,
            Set<String> externalFrontier,
            ArrayDeque<Map.Entry<ProjectFile, String>> queue) {
        ImportBinder.ImportBinding binding =
                adapter.importBinderOf(currentFile).bindings().get(localName);
        if (binding == null || binding.importedName() == null) {
            return false;
        }

        ExportUsageGraphLanguageAdapter.ResolutionOutcome resolved =
                adapter.resolveModule(currentFile, binding.moduleSpecifier());
        if (resolved.resolved().isEmpty()) {
            resolved.externalFrontier().ifPresent(externalFrontier::add);
            addFrontier(frontier, externalFrontier, binding.moduleSpecifier(), currentFile, adapter);
            return true;
        }

        queue.add(Map.entry(resolved.resolved().orElseThrow(), binding.importedName()));
        return true;
    }

    private static Set<CodeUnit> resolveLocalExport(
            ProjectFile file, String localName, ExportUsageGraphLanguageAdapter adapter) {
        var matches = new LinkedHashSet<CodeUnit>();
        for (CodeUnit cu : adapter.definitionsOf(localName)) {
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
            CodeUnit ownerClass, String memberName, boolean instanceReceiver, ExportUsageGraphLanguageAdapter adapter) {
        return resolveClassMember(ownerClass.source(), ownerClass.identifier(), memberName, instanceReceiver, adapter);
    }

    private static @Nullable CodeUnit resolveClassMember(
            ProjectFile sourceFile,
            String ownerClassName,
            String memberName,
            boolean instanceReceiver,
            ExportUsageGraphLanguageAdapter adapter) {
        CodeUnit exact = adapter.exactMember(sourceFile, ownerClassName, memberName, instanceReceiver);
        if (exact != null) {
            return exact;
        }

        ExportIndex.ClassMember declared = adapter.exportIndexOf(sourceFile).classMembers().stream()
                .filter(member -> member.ownerClassName().equals(ownerClassName))
                .filter(member -> member.memberName().equals(memberName))
                .filter(member -> instanceReceiver ? !member.staticMember() : member.staticMember())
                .findFirst()
                .orElse(null);
        if (declared == null) {
            return null;
        }

        return syntheticMember(sourceFile, ownerClassName, declared);
    }

    private static Set<ProjectFile> collectReferencingFiles(
            ProjectFile start,
            ExportUsageGraphLanguageAdapter adapter,
            Map<ProjectFile, Set<ProjectFile>> reverseReexports,
            int maxFiles)
            throws InterruptedException {
        adapter.ensureImportReverseIndexPopulated();
        var seen = new LinkedHashSet<ProjectFile>();
        var queue = new ArrayDeque<ProjectFile>();
        queue.add(start);
        seen.add(start);

        while (!queue.isEmpty() && seen.size() < maxFiles) {
            ProjectFile current = queue.removeFirst();
            var neighbors = new LinkedHashSet<ProjectFile>();
            neighbors.addAll(adapter.referencingFilesOf(current));
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

    private static String normalizedMemberName(CodeUnit codeUnit) {
        return stripMemberSuffix(codeUnit.identifier());
    }

    private static boolean ownerMatchesHeritage(
            String queryOwner, String resolvedOwner, Map<String, Set<String>> heritageEdges) {
        if (queryOwner.isEmpty() || resolvedOwner.isEmpty()) {
            return false;
        }
        if (queryOwner.equals(resolvedOwner)) {
            return true;
        }
        var queue = new ArrayDeque<String>();
        var visited = new HashSet<String>();
        queue.add(resolvedOwner);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            Set<String> parents = heritageEdges.get(current);
            if (parents == null || parents.isEmpty()) {
                continue;
            }
            if (parents.contains(queryOwner)) {
                return true;
            }
            parents.forEach(queue::addLast);
        }
        return false;
    }

    private static boolean isMemberTarget(CodeUnit target) {
        return (target.kind() == CodeUnitType.FIELD || target.kind() == CodeUnitType.FUNCTION)
                && !qualifiedOwnerKey(target).isEmpty();
    }

    private static String ownerNameOf(CodeUnit codeUnit) {
        String shortName = codeUnit.shortName();
        int lastDot = shortName.lastIndexOf('.');
        if (lastDot <= 0) {
            return "";
        }
        return shortName.substring(0, lastDot);
    }

    private static String qualifiedOwnerKey(CodeUnit codeUnit) {
        String ownerName = ownerNameOf(codeUnit);
        if (ownerName.isEmpty()) {
            return "";
        }
        return qualifiedClassKey(codeUnit.source(), ownerName);
    }

    private static String qualifiedClassKey(CodeUnit codeUnit) {
        return qualifiedClassKey(codeUnit.source(), codeUnit.identifier());
    }

    private static String qualifiedClassKey(ProjectFile file, String className) {
        return canonicalFileKey(file) + ":" + className;
    }

    private static String canonicalFileKey(ProjectFile file) {
        return PathNormalizer.canonicalizeForProject(file.getRelPath().toString(), file.getRoot());
    }

    private static CodeUnit syntheticMember(
            ProjectFile sourceFile, String ownerClassName, ExportIndex.ClassMember declaredMember) {
        String shortName = ownerClassName + "." + declaredMember.memberName();
        return switch (declaredMember.kind()) {
            case FUNCTION -> CodeUnit.fn(sourceFile, "", shortName).withSynthetic(true);
            case FIELD -> CodeUnit.field(sourceFile, "", shortName).withSynthetic(true);
            default -> CodeUnit.field(sourceFile, "", shortName).withSynthetic(true);
        };
    }

    private static String stripMemberSuffix(String name) {
        int marker = name.indexOf('$');
        return marker >= 0 ? name.substring(0, marker) : name;
    }
}
