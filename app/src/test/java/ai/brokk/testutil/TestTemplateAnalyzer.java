package ai.brokk.testutil;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ITemplateAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TemplateAnalysisResult;
import ai.brokk.analyzer.TreeSitterAnalyzer;
import ai.brokk.project.ICoreProject;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TestTemplateAnalyzer implements ITemplateAnalyzer {
    private final String name;
    private final Map<CodeUnit, Set<String>> templateSources;

    public TestTemplateAnalyzer(String name, Map<CodeUnit, Set<String>> templateSources) {
        this.name = name;
        this.templateSources = templateSources;
    }

    @Override
    public boolean isApplicable(ICoreProject project) {
        return true;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String internalName() {
        return name.toUpperCase();
    }

    @Override
    public List<String> getSupportedExtensions() {
        return List.of("html");
    }

    @Override
    public void onHostSignal(
            String signal, Map<String, Object> payload, TreeSitterAnalyzer.AnalyzerState globalState) {}

    @Override
    public TemplateAnalysisResult analyzeTemplate(
            IAnalyzer hostAnalyzer, ProjectFile templateFile, CodeUnit hostClass) {
        return new TemplateAnalysisResult(internalName(), templateFile, Set.of(), List.of());
    }

    @Override
    public List<TemplateAnalysisResult> snapshotState() {
        return List.of();
    }

    @Override
    public void restoreState(List<TemplateAnalysisResult> state) {}

    @Override
    public Set<String> getTemplateSources(CodeUnit hostClass) {
        return templateSources.getOrDefault(hostClass, Set.of());
    }
}
