package ai.brokk.analyzer.scala;

import ai.brokk.IProject;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.ScalaAnalyzer;
import java.util.List;

public class ScalaLanguage implements Language {

    private final List<String> extensions = List.of("scala");

    @Override
    public List<String> getExtensions() {
        return extensions;
    }

    @Override
    public String name() {
        return "Scala";
    }

    @Override
    public String internalName() {
        return "SCALA";
    }

    @Override
    public IAnalyzer createAnalyzer(IProject project) {
        return new ScalaAnalyzer(project);
    }

    @Override
    public IAnalyzer loadAnalyzer(IProject project) {
        return createAnalyzer(project);
    }

    @Override
    public String toString() {
        return name();
    }
}
