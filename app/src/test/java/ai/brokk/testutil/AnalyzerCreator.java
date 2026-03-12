package ai.brokk.testutil;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.MultiAnalyzer;
import ai.brokk.analyzer.TreeSitterAnalyzer;
import ai.brokk.project.IProject;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

public class AnalyzerCreator {

    public static TreeSitterAnalyzer createTreeSitterAnalyzer(IProject project) {
        var languages = project.getAnalyzerLanguages().stream()
                .filter(l -> l != Languages.NONE)
                .sorted(Comparator.comparing(Language::internalName))
                .toList();

        if (languages.isEmpty()) {
            throw new NoSupportedAnalyzerForTestProjectException(Languages.NONE);
        }

        // Pick the first deterministic language
        var language = languages.getFirst();
        var analyzer = language.createAnalyzer(project);
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
    /**
     * Creates an appropriate analyzer for the given project based on its configured languages.
     *
     * @param project the project to create an analyzer for.
     * @return an initialized IAnalyzer (TreeSitter, MultiAnalyzer, or Disabled).
     */
    public static IAnalyzer createFor(IProject project) {
        var languages = project.getAnalyzerLanguages();
        var activeLanguages =
                languages.stream().filter(l -> l != Languages.NONE).toList();

        if (activeLanguages.isEmpty()) {
            return Languages.NONE.createAnalyzer(project);
        }

        if (activeLanguages.size() == 1) {
            return activeLanguages.getFirst().createAnalyzer(project).update();
        }

        return createMultiAnalyzer(project, activeLanguages.toArray(new Language[0]))
                .update();
    }

    /**
     * Creates an appropriate analyzer for the given project based on the specific languages.
     *
     * @param project the project to create an analyzer for.
     * @param languages the languages to include in the analyzer.
     * @return an IAnalyzer for the specified languages.
     */
    public static IAnalyzer createFor(IProject project, Language... languages) {
        if (languages.length == 0) {
            return createFor(project);
        }
        if (languages.length == 1) {
            return languages[0].createAnalyzer(project);
        }
        return createMultiAnalyzer(project, languages);
    }

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
