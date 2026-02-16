package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ImportAnalysisProvider;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TypeHierarchyProvider;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A {@link CandidateFileProvider} that uses the import graph and type hierarchy to find potential usage files.
 */
public final class ImportGraphCandidateProvider implements CandidateFileProvider {

    @Override
    public Set<ProjectFile> findCandidates(CodeUnit target, IAnalyzer analyzer) throws InterruptedException {
        Set<ProjectFile> candidates = new HashSet<>();

        // 1. Identify Polymorphic Targets (target + descendants)
        Set<CodeUnit> allTargets = new HashSet<>();
        allTargets.add(target);
        analyzer.as(TypeHierarchyProvider.class)
                .map(provider -> provider.getPolymorphicMatches(target, analyzer))
                .ifPresent(allTargets::addAll);

        // 2. Identify Defining Files & Siblings
        Set<ProjectFile> sourceFiles = allTargets.stream().map(CodeUnit::source).collect(Collectors.toSet());

        for (ProjectFile sourceFile : sourceFiles) {
            candidates.add(sourceFile);

            // Same-Package Approximation: Siblings in the same directory
            Path parent = sourceFile.getParent();
            var language = Languages.fromExtension(sourceFile.extension());
            Set<ProjectFile> siblings = analyzer.getProject().getAnalyzableFiles(language).stream()
                    .filter(f -> f.getParent().equals(parent))
                    .collect(Collectors.toSet());
            candidates.addAll(siblings);
        }

        // 3. Identify Direct Importers
        analyzer.as(ImportAnalysisProvider.class).ifPresent(provider -> {
            Set<ProjectFile> sourceSet = new HashSet<>(candidates);
            for (ProjectFile sourceFile : sourceSet) {
                candidates.addAll(provider.referencingFilesOf(sourceFile));
            }
        });

        return candidates;
    }
}
