package ai.brokk.testutil;

import ai.brokk.IProject;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.TreeSitterAnalyzer;

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
        if (analyzer instanceof TreeSitterAnalyzer treeSitterAnalyzer) {
            return treeSitterAnalyzer;
        } else {
            throw new NoSupportedAnalyzerForTestProjectException(language);
        }
    }

    static class NoSupportedAnalyzerForTestProjectException extends RuntimeException {
        public NoSupportedAnalyzerForTestProjectException(Language language) {
            super("Analyzer not supported for the given project! Detected language is: " + language.name());
        }
    }
}
