package ai.brokk.testutil;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.project.IProject;

/**
 * Extension of {@link IProject} for testing purposes, providing convenient access to analyzers.
 */
public interface ITestProject extends IProject {

    /**
     * Returns an analyzer configured for the project's detected language(s).
     *
     * @return an {@link IAnalyzer} for the project.
     */
    IAnalyzer getAnalyzer();

    /**
     * Returns an analyzer configured for the specific languages.
     *
     * @param languages the languages to include in the analyzer.
     * @return an {@link IAnalyzer} for the specified languages.
     */
    default IAnalyzer getAnalyzer(Language... languages) {
        return AnalyzerCreator.createFor(this, languages);
    }
}
