package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import java.util.Set;

/**
 * Strategy interface for identifying potential files that may contain usages of a {@link CodeUnit}.
 */
public interface CandidateFileProvider {
    /**
     * Returns a set of files that might contain references to the target code unit.
     * Implementations should favor false positives over false negatives.
     *
     * @param target the code unit to find usages for
     * @param analyzer the analyzer to use for discovery
     * @return a set of candidate files
     * @throws InterruptedException if the search is interrupted
     */
    Set<ProjectFile> findCandidates(CodeUnit target, IAnalyzer analyzer) throws InterruptedException;
}
