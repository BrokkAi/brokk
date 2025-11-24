package ai.brokk.analyzer.scala;

import ai.brokk.project.IProject;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.ScalaAnalyzer;
import ai.brokk.analyzer.TreeSitterStateIO;
import java.util.Set;

public class ScalaLanguage implements Language {

    private final Set<String> extensions = Set.of("scala");

    @Override
    public Set<String> getExtensions() {
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
        var storage = getStoragePath(project);
        return TreeSitterStateIO.load(storage)
                .map(state -> (IAnalyzer) ScalaAnalyzer.fromState(project, state))
                .orElseGet(() -> createAnalyzer(project));
    }

    @Override
    public String toString() {
        return name();
    }
}
