package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.tools.SearchTools;
import java.util.List;
import java.util.Set;

/**
 * A {@link CandidateFileProvider} that uses a raw text search to find potential usage files.
 * This is useful as a fallback when the import graph is incomplete or unavailable.
 */
public final class TextSearchCandidateProvider implements CandidateFileProvider {

    @Override
    public Set<ProjectFile> findCandidates(CodeUnit target, IAnalyzer analyzer) throws InterruptedException {
        String identifier = target.identifier();
        Language lang = Languages.fromExtension(target.source().extension());

        // Use a fast substring scan to prefilter candidate files by the raw identifier
        Set<ProjectFile> filesToSearch = analyzer.getProject().getAnalyzableFiles(lang);
        var patterns = SearchTools.compilePatterns(List.of(identifier));

        return SearchTools.findFilesContainingPatterns(patterns, filesToSearch).matches();
    }
}
