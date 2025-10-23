package io.github.jbellis.brokk.analyzer.scala;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.ScalaAnalyzer;
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
    public boolean providesSummaries() {
        return true;
    }

    @Override
    public boolean providesSourceCode() {
        return true;
    }

    @Override
    public boolean providesInterproceduralAnalysis() {
        return true;
    }
}
