package ai.brokk.tools;

import ai.brokk.agents.RefactoringService;
import ai.brokk.agents.RefactoringService.RefactoringLineRanges;
import ai.brokk.agents.RefactoringService.RefactoringResult;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.context.DiffService.CumulativeChanges;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Helper for the RM-shading evaluation harness. Produces both vanilla and
 * RefactoringMiner-shaded review diffs from a single CumulativeChanges scope.
 */
public final class RmShadingEvalHelper {

    private RmShadingEvalHelper() {}

    /** Result of producing both diff variants for one commit range. */
    public record DiffPair(
            String vanillaDiff,
            String rmShadedDiff,
            String refactoringSummary,
            int refactoringCount,
            int vanillaDiffLineCount,
            int rmShadedDiffLineCount) {

        public static DiffPair empty(String emptyDiff) {
            int lines = emptyDiff.isEmpty() ? 0 : (int) emptyDiff.lines().count();
            return new DiffPair(emptyDiff, emptyDiff, "", 0, lines, lines);
        }
    }

    /**
     * Produces vanilla and RM-shaded review diffs for the given changes.
     *
     * @param changes cumulative file changes (e.g. from DiffService.computeCumulativeDiff)
     * @param analyzer optional analyzer for method names in hunk headers; null to omit
     * @return both diff strings, refactoring summary, counts, and line counts
     */
    @Blocking
    public static DiffPair produceBothDiffs(CumulativeChanges changes, @Nullable IAnalyzer analyzer) {
        if (changes.perFileChanges().isEmpty()) {
            String empty = "";
            return DiffPair.empty(empty);
        }

        RefactoringService refactoringService = new RefactoringService();
        RefactoringResult refactoringResult = refactoringService.detectRefactorings(changes.perFileChanges());

        RefactoringLineRanges refactoringLineRanges =
                refactoringResult.hasRefactorings() ? refactoringService.extractLineRanges(refactoringResult) : null;

        String vanillaDiff = changes.toReviewDiff(analyzer, null);

        String rmShadedDiff = changes.toReviewDiff(analyzer, refactoringLineRanges);
        if (refactoringResult.hasRefactorings()) {
            rmShadedDiff = refactoringResult.summary() + "\n---\n\n" + rmShadedDiff;
        }

        int refactoringCount = refactoringResult.refactorings().size();
        int vanillaLines = (int) vanillaDiff.lines().count();
        int rmShadedLines = (int) rmShadedDiff.lines().count();

        return new DiffPair(
                vanillaDiff,
                rmShadedDiff,
                refactoringResult.summary(),
                refactoringCount,
                vanillaLines,
                rmShadedLines);
    }
}
