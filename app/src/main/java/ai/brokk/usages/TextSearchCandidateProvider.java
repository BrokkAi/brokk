package ai.brokk.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.usages.CandidateFileProvider;
import java.util.Set;

/**
 * Backward-compatible wrapper for the shared {@code ai.brokk.analyzer.usages.TextSearchCandidateProvider}.
 *
 * <p>The core implementation lives in {@code :brokk-shared} under {@code ai.brokk.analyzer.usages}.
 */
public final class TextSearchCandidateProvider implements CandidateFileProvider {
    private final ai.brokk.analyzer.usages.TextSearchCandidateProvider delegate =
            new ai.brokk.analyzer.usages.TextSearchCandidateProvider();

    @Override
    public Set<ProjectFile> findCandidates(
            CodeUnit target, IAnalyzer analyzer) throws InterruptedException {
        return delegate.findCandidates(target, analyzer);
    }
}
