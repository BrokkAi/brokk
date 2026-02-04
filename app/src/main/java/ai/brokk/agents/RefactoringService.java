package ai.brokk.agents;

import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.git.GitRepoData.FileDiff;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.refactoringminer.api.GitHistoryRefactoringMiner;
import org.refactoringminer.api.Refactoring;
import org.refactoringminer.api.RefactoringHandler;
import gr.uom.java.xmi.diff.CodeRange;
import org.refactoringminer.rm1.GitHistoryRefactoringMinerImpl;

/**
 * Service for detecting refactorings in code changes using RefactoringMiner.
 *
 * <p>RefactoringMiner provides semantic understanding of code changes, detecting
 * 105 types of refactorings with 99.9% precision. This helps Guided Review
 * distinguish mechanical refactorings from behavioral changes.
 */
public class RefactoringService {
    private static final Logger logger = LogManager.getLogger(RefactoringService.class);

    /**
     * Languages supported by RefactoringMiner for refactoring detection.
     * RefactoringMiner also supports Kotlin, but Brokk's Languages class does not define it.
     */
    private static final Set<Language> SUPPORTED_LANGUAGES = Set.of(Languages.JAVA, Languages.PYTHON);

    /**
     * A detected refactoring with its location and description.
     */
    public record DetectedRefactoring(
            String type,
            String description,
            List<Location> leftSideLocations,
            List<Location> rightSideLocations) {

        public record Location(
                String filePath,
                int startLine,
                int endLine,
                String codeElementType,
                String codeElement) {}
    }

    /**
     * Result of refactoring detection including all detected refactorings
     * and a human-readable summary.
     */
    public record RefactoringResult(List<DetectedRefactoring> refactorings, String summary) {

        /**
         * Returns true if any refactorings were detected.
         */
        public boolean hasRefactorings() {
            return !refactorings.isEmpty();
        }

        /**
         * Returns an empty result with no refactorings.
         */
        public static RefactoringResult empty() {
            return new RefactoringResult(List.of(), "");
        }
    }

    /**
     * Detects refactorings in the given file diffs.
     *
     * <p>Uses RefactoringMiner's in-memory analysis to detect refactorings
     * without requiring a Git repository. Only processes files in supported
     * languages (Java, Kotlin, Python).
     *
     * @param fileDiffs the file changes to analyze
     * @return detection result with refactorings and summary
     */
    @Blocking
    public RefactoringResult detectRefactorings(List<FileDiff> fileDiffs) {
        // Filter to supported languages only
        List<FileDiff> supportedDiffs = fileDiffs.stream()
                .filter(this::isSupportedLanguage)
                .toList();

        if (supportedDiffs.isEmpty()) {
            logger.debug("No files in supported languages for refactoring detection");
            return RefactoringResult.empty();
        }

        // Build before/after content maps
        Map<String, String> beforeContents = supportedDiffs.stream()
                .filter(fd -> fd.oldFile() != null && !fd.oldText().isEmpty())
                .collect(Collectors.toMap(
                        fd -> fd.oldFile().toString(),
                        FileDiff::oldText,
                        (a, b) -> a)); // Handle duplicate keys

        Map<String, String> afterContents = supportedDiffs.stream()
                .filter(fd -> fd.newFile() != null && !fd.newText().isEmpty())
                .collect(Collectors.toMap(
                        fd -> fd.newFile().toString(),
                        FileDiff::newText,
                        (a, b) -> a)); // Handle duplicate keys

        if (beforeContents.isEmpty() && afterContents.isEmpty()) {
            return RefactoringResult.empty();
        }

        try {
            List<Refactoring> refactorings = runRefactoringMiner(beforeContents, afterContents);

            List<DetectedRefactoring> detected = refactorings.stream()
                    .map(this::toDetectedRefactoring)
                    .toList();

            String summary = buildSummary(detected);

            logger.info("Detected {} refactorings in {} files", detected.size(), supportedDiffs.size());
            return new RefactoringResult(detected, summary);

        } catch (Exception e) {
            logger.warn("RefactoringMiner analysis failed, falling back to text diff", e);
            return RefactoringResult.empty();
        }
    }

    private boolean isSupportedLanguage(FileDiff fd) {
        var file = fd.newFile() != null ? fd.newFile() : fd.oldFile();
        if (file == null) {
            return false;
        }
        Language lang = Languages.fromExtension(file.extension());
        return SUPPORTED_LANGUAGES.contains(lang);
    }

    private List<Refactoring> runRefactoringMiner(
            Map<String, String> beforeContents,
            Map<String, String> afterContents) throws Exception {
        GitHistoryRefactoringMiner miner = new GitHistoryRefactoringMinerImpl();
        List<Refactoring> result = new ArrayList<>();

        miner.detectAtFileContents(beforeContents, afterContents, new RefactoringHandler() {
            @Override
            public void handle(String commitId, List<Refactoring> refactorings) {
                result.addAll(refactorings);
            }
        });

        return result;
    }

    private DetectedRefactoring toDetectedRefactoring(Refactoring ref) {
        List<DetectedRefactoring.Location> leftLocations = ref.leftSide().stream()
                .map(this::toLocation)
                .filter(Objects::nonNull)
                .toList();

        List<DetectedRefactoring.Location> rightLocations = ref.rightSide().stream()
                .map(this::toLocation)
                .filter(Objects::nonNull)
                .toList();

        return new DetectedRefactoring(
                ref.getRefactoringType().getDisplayName(),
                ref.toString(),
                leftLocations,
                rightLocations);
    }

    private DetectedRefactoring.Location toLocation(CodeRange codeRange) {
        if (codeRange == null) {
            return null;
        }
        String codeElementType = codeRange.getCodeElementType() != null
                ? codeRange.getCodeElementType().name()
                : "UNKNOWN";
        return new DetectedRefactoring.Location(
                codeRange.getFilePath(),
                codeRange.getStartLine(),
                codeRange.getEndLine(),
                codeElementType,
                codeRange.getCodeElement());
    }

    /**
     * Builds a human-readable summary of detected refactorings for the LLM.
     */
    private String buildSummary(List<DetectedRefactoring> refactorings) {
        if (refactorings.isEmpty()) {
            return "";
        }

        var sb = new StringBuilder();
        sb.append("## Detected Refactorings\n\n");
        sb.append("The following refactorings were detected in this change. ");
        sb.append("Refactorings are typically behavior-preserving transformations.\n\n");

        // Group by refactoring type
        Map<String, List<DetectedRefactoring>> byType = refactorings.stream()
                .collect(Collectors.groupingBy(DetectedRefactoring::type));

        for (var entry : byType.entrySet()) {
            String type = entry.getKey();
            List<DetectedRefactoring> refs = entry.getValue();

            sb.append("### ").append(type).append(" (").append(refs.size()).append(")\n");
            for (var ref : refs) {
                sb.append("- ").append(formatRefactoringDescription(ref)).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String formatRefactoringDescription(DetectedRefactoring ref) {
        // Extract a concise description from the full description
        String desc = ref.description();

        // The full description can be verbose; try to extract key info
        // Format: "Refactoring Type description from X to Y"
        // We'll use the description as-is but may truncate if too long
        if (desc.length() > 200) {
            desc = desc.substring(0, 197) + "...";
        }

        return desc;
    }

    /**
     * Checks if any of the file diffs contain files in supported languages.
     */
    public boolean hasSupportedFiles(List<FileDiff> fileDiffs) {
        return fileDiffs.stream().anyMatch(this::isSupportedLanguage);
    }

    /**
     * Line ranges affected by refactorings, keyed by file path.
     * Used to filter diff hunks that are entirely explained by refactorings.
     */
    public record RefactoringLineRanges(
            Map<String, List<LineRange>> oldFileRanges,
            Map<String, List<LineRange>> newFileRanges) {

        public record LineRange(int startLine, int endLine) {
            /**
             * Returns true if the given line range is entirely contained within this range.
             */
            public boolean contains(int start, int end) {
                return start >= startLine && end <= endLine;
            }

            /**
             * Returns true if the given line range overlaps with this range.
             */
            public boolean overlaps(int start, int end) {
                return start <= endLine && end >= startLine;
            }
        }

        public static RefactoringLineRanges empty() {
            return new RefactoringLineRanges(Map.of(), Map.of());
        }

        /**
         * Checks if the given line range in the old version is covered by a refactoring.
         */
        public boolean isOldRangeCovered(String filePath, int startLine, int endLine) {
            var ranges = oldFileRanges.get(filePath);
            if (ranges == null) return false;
            return ranges.stream().anyMatch(r -> r.contains(startLine, endLine));
        }

        /**
         * Checks if the given line range in the new version is covered by a refactoring.
         */
        public boolean isNewRangeCovered(String filePath, int startLine, int endLine) {
            var ranges = newFileRanges.get(filePath);
            if (ranges == null) return false;
            return ranges.stream().anyMatch(r -> r.contains(startLine, endLine));
        }

        /**
         * Checks if the given line range in the old version overlaps with a refactoring.
         */
        public boolean oldRangeOverlaps(String filePath, int startLine, int endLine) {
            var ranges = oldFileRanges.get(filePath);
            if (ranges == null) return false;
            return ranges.stream().anyMatch(r -> r.overlaps(startLine, endLine));
        }

        /**
         * Checks if the given line range in the new version overlaps with a refactoring.
         */
        public boolean newRangeOverlaps(String filePath, int startLine, int endLine) {
            var ranges = newFileRanges.get(filePath);
            if (ranges == null) return false;
            return ranges.stream().anyMatch(r -> r.overlaps(startLine, endLine));
        }
    }

    /**
     * Extracts line ranges from detected refactorings for use in diff filtering.
     * The ranges represent code regions that are "explained" by the refactoring
     * and can potentially be masked from the diff.
     */
    public RefactoringLineRanges extractLineRanges(RefactoringResult result) {
        if (!result.hasRefactorings()) {
            return RefactoringLineRanges.empty();
        }

        Map<String, List<RefactoringLineRanges.LineRange>> oldRanges = new java.util.HashMap<>();
        Map<String, List<RefactoringLineRanges.LineRange>> newRanges = new java.util.HashMap<>();

        for (var ref : result.refactorings()) {
            // Add left-side locations (old file positions)
            for (var loc : ref.leftSideLocations()) {
                oldRanges.computeIfAbsent(loc.filePath(), k -> new ArrayList<>())
                        .add(new RefactoringLineRanges.LineRange(loc.startLine(), loc.endLine()));
            }

            // Add right-side locations (new file positions)
            for (var loc : ref.rightSideLocations()) {
                newRanges.computeIfAbsent(loc.filePath(), k -> new ArrayList<>())
                        .add(new RefactoringLineRanges.LineRange(loc.startLine(), loc.endLine()));
            }
        }

        return new RefactoringLineRanges(
                Map.copyOf(oldRanges),
                Map.copyOf(newRanges));
    }
}
