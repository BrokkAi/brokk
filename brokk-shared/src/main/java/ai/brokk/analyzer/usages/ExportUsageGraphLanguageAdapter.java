package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * Language-specific hooks for the exported-symbol usage graph.
 *
 * <p>The graph owns traversal and hit matching. Adapters own structured extraction and module resolution.
 */
public interface ExportUsageGraphLanguageAdapter {

    ExportIndex exportIndexOf(ProjectFile file);

    ImportBinder importBinderOf(ProjectFile file);

    Set<ReferenceCandidate> usageCandidatesOf(ProjectFile file, ImportBinder binder);

    Set<CodeUnit> definitionsOf(String localName);

    default Set<ResolvedReceiverCandidate> resolvedReceiverCandidatesOf(ProjectFile file, ImportBinder binder) {
        return Set.of();
    }

    ResolutionOutcome resolveModule(ProjectFile importingFile, String moduleSpecifier);

    default Map<ProjectFile, Set<ProjectFile>> reverseReexportIndex() {
        return Map.of();
    }

    default Set<ProjectFile> referencingFilesOf(ProjectFile file) throws InterruptedException {
        return Set.of();
    }

    default void ensureImportReverseIndexPopulated() throws InterruptedException {}

    default Map<String, Set<String>> heritageIndex() {
        return Map.of();
    }

    default @Nullable CodeUnit exactMember(
            ProjectFile sourceFile, String ownerClassName, String memberName, boolean instanceReceiver) {
        return null;
    }

    default ExportResolutionData cachedExportResolution(
            ProjectFile definingFile,
            String exportName,
            int maxReexportDepth,
            Supplier<ExportResolutionData> supplier) {
        return supplier.get();
    }

    record ResolutionOutcome(Optional<ProjectFile> resolved, Optional<String> externalFrontier) {
        public static ResolutionOutcome resolved(ProjectFile file) {
            return new ResolutionOutcome(Optional.of(file), Optional.empty());
        }

        public static ResolutionOutcome external(String specifier) {
            return new ResolutionOutcome(Optional.empty(), Optional.of(specifier));
        }

        public static ResolutionOutcome empty() {
            return new ResolutionOutcome(Optional.empty(), Optional.empty());
        }
    }
}
