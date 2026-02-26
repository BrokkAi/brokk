package ai.brokk.testutil;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.project.IProject;
import java.util.Set;

/**
 * Extension of {@link IProject} for testing purposes, providing convenient access to analyzers.
 */
public interface ITestProject extends IProject {

    /**
     * Returns an analyzer configured for the project's detected language(s).
     *
     * @return an {@link IAnalyzer} for the project.
     */
    default IAnalyzer getAnalyzer() {
        Set<Language> languages = getAnalyzerLanguages();
        return getAnalyzer(languages.toArray(new Language[0]));
    }

    /**
     * Returns an analyzer configured for the specific languages.
     *
     * @param languages the languages to include in the analyzer.
     * @return an {@link IAnalyzer} for the specified languages.
     */
    default IAnalyzer getAnalyzer(Language... languages) {
        if (languages.length == 0) {
            // Fallback to NONE if no languages specified
            return AnalyzerCreator.createMultiAnalyzer(this);
        }
        if (languages.length == 1) {
            return languages[0].createAnalyzer(this);
        }
        return AnalyzerCreator.createMultiAnalyzer(this, languages);
    }
}
