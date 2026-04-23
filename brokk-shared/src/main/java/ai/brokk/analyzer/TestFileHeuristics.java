package ai.brokk.analyzer;

import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/** Shared test-file detection: semantic when available, otherwise filename/path heuristics. */
public final class TestFileHeuristics {
    private TestFileHeuristics() {}

    // Regex to identify test files. Matches the word "test"/"tests" (case-insensitive)
    // when it appears as its own path segment or at a camel-case boundary, as well as
    // common JS/TS conventions: *.spec.<ext>, *.test.<ext>, and files under __tests__/.
    private static final Pattern TEST_FILE_PATTERN = Pattern.compile(".*"
            + "(?:"
            + "(?:[/\\\\.]|\\b|_|(?<=[a-z])(?=[A-Z])|(?<=[A-Z]))"
            + "(?i:tests?)"
            + "(?:[/\\\\.]|\\b|_|(?=[A-Z][^a-z])|(?=[A-Z][a-z])|$)"
            + "|"
            + "(?i:\\.(?:spec|test)\\.[^/\\\\.]+$)"
            + ")"
            + ".*");

    public static boolean isTestFile(ProjectFile file, @Nullable IAnalyzer analyzer) {
        if (analyzer != null && !analyzer.isEmpty()) {
            IAnalyzer effectiveAnalyzer = analyzer;
            if (analyzer instanceof MultiAnalyzer multi) {
                var lang = Languages.fromExtension(file.extension());
                effectiveAnalyzer = multi.getDelegates().get(lang);
            }

            if (effectiveAnalyzer != null
                    && effectiveAnalyzer.as(TestDetectionProvider.class).isPresent()) {
                return effectiveAnalyzer.containsTests(file);
            }
        }

        return TEST_FILE_PATTERN.matcher(file.toString()).matches();
    }
}
