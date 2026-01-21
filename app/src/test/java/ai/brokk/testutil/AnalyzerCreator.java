package ai.brokk.testutil;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.MultiAnalyzer;
import ai.brokk.analyzer.TreeSitterAnalyzer;
import ai.brokk.project.IProject;
import java.util.HashMap;
import java.util.Map;

public class AnalyzerCreator {

    /**
     * Creates the TreeSitterAnalyzer for the given project if one supports the project language.
     *
     * @param project the project to instantiate a TreeSitter analyzer for.
     * @return the corresponding TreeSitterAnalyzer.
     * @throws NoSupportedAnalyzerForTestProjectException if the detected language does not create an analyzer extending {@link TreeSitterAnalyzer}
     */
    public static TreeSitterAnalyzer createTreeSitterAnalyzer(IProject project) {
        var language = project.getBuildLanguage();
        var analyzer = language.createAnalyzer(project);

        if (analyzer instanceof TreeSitterAnalyzer tsa) {
            return tsa;
        }

        if (analyzer instanceof MultiAnalyzer multi) {
            return multi.getDelegates().values().stream()
                    .filter(TreeSitterAnalyzer.class::isInstance)
                    .map(TreeSitterAnalyzer.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new NoSupportedAnalyzerForTestProjectException(language));
        }

        return (TreeSitterAnalyzer) analyzer.subAnalyzer(language)
                .orElseThrow(() -> new NoSupportedAnalyzerForTestProjectException(language));
    }

    /**
     * Creates a MultiAnalyzer wrapping per-language analyzers for the given project and languages.
     *
     * <p>This is a convenience method for testing scenarios with multiple languages. Each language
     * is used to create an analyzer via {@link Language#createAnalyzer(IProject)}, which is then
     * wrapped in a single {@link MultiAnalyzer} that delegates to the appropriate language-specific
     * analyzer based on file extension.
     *
     * @param project the project to create analyzers for.
     * @param languages the languages to analyze (varargs).
     * @return a MultiAnalyzer wrapping per-language analyzers.
     */
    public static MultiAnalyzer createMultiAnalyzer(IProject project, Language... languages) {
        Map<Language, IAnalyzer> delegates = new HashMap<>();
        for (Language language : languages) {
            delegates.put(language, language.createAnalyzer(project));
        }
        return new MultiAnalyzer(delegates);
    }

    static class NoSupportedAnalyzerForTestProjectException extends RuntimeException {
        public NoSupportedAnalyzerForTestProjectException(Language language) {
            super("Analyzer not supported for the given project! Detected language is: " + language.name());
        }
    }
}
