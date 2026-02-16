package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TreeSitterAnalyzer;
import ai.brokk.analyzer.TypeHierarchyProvider;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A {@link CandidateFileProvider} that uses the import graph and type hierarchy to find potential usage files.
 */
public final class ImportGraphCandidateProvider implements CandidateFileProvider {

    @Override
    public Set<ProjectFile> findCandidates(CodeUnit target, IAnalyzer analyzer) throws InterruptedException {
        Set<ProjectFile> candidates = new HashSet<>();

        // 1. Identify Polymorphic Targets
        Collection<CodeUnit> targets = analyzer.as(TypeHierarchyProvider.class)
                .map(provider -> provider.getPolymorphicMatches(target, analyzer))
                .orElseGet(() -> List.of(target));

        // 2. Identify Defining Files & Siblings
        Set<ProjectFile> sourceFiles = targets.stream().map(CodeUnit::source).collect(Collectors.toSet());

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
        if (analyzer instanceof TreeSitterAnalyzer tsAnalyzer) {
            var language = Languages.fromExtension(target.source().extension());
            Set<ProjectFile> allFiles = analyzer.getProject().getAnalyzableFiles(language);

            // We want to find files that import ANY of the candidate files (definitions or siblings)
            Set<ProjectFile> sourceSet = new HashSet<>(candidates);

            for (ProjectFile potentialImporter : allFiles) {
                if (sourceSet.contains(potentialImporter)) {
                    continue;
                }
                var imports = tsAnalyzer.importInfoOf(potentialImporter);
                for (ProjectFile sourceFile : sourceSet) {
                    if (tsAnalyzer.couldImportFile(imports, sourceFile)) {
                        candidates.add(potentialImporter);
                        break;
                    }
                }
            }
        }

        return candidates;
    }
}
