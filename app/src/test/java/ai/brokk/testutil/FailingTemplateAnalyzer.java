package ai.brokk.testutil;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ITemplateAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TemplateAnalysisResult;
import ai.brokk.analyzer.TreeSitterAnalyzer;
import ai.brokk.project.IProject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class FailingTemplateAnalyzer implements ITemplateAnalyzer {
    private final String name;
    private final RuntimeException failure;

    public FailingTemplateAnalyzer(String name, RuntimeException failure) {
        this.name = name;
        this.failure = failure;
    }

    public FailingTemplateAnalyzer(String name, String message) {
        this(name, new RuntimeException(message));
    }

    @Override
    public boolean isApplicable(IProject project) {
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
    public Set<ProjectFile> getTemplateFiles(CodeUnit hostClass, ai.brokk.IContextManager contextManager) {
        return Set.of();
    }

    @Override
    public Optional<String> summarizeTemplate(ProjectFile templateFile, ai.brokk.IContextManager contextManager) {
        return Optional.empty();
    }

    @Override
    public Set<String> getTemplateSources(CodeUnit hostClass) {
        throw failure;
    }
}
