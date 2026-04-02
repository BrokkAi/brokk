package ai.brokk.analyzer;

import ai.brokk.analyzer.frameworks.AngularTemplateAnalyzer;
import ai.brokk.project.IProject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Blocking;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class FrameworkTemplates {
    private FrameworkTemplates() {}

    @Blocking
    public static List<FrameworkTemplate> discoverTemplates(IProject project, Set<Language> languages) {
        List<FrameworkTemplate> templates = new ArrayList<>();

        if (languages.contains(Languages.TYPESCRIPT)) {
            var angular = new AngularTemplateAnalyzer();
            if (angular.isApplicable(project)) {
                templates.add(angular);
            }
        }

        return templates;
    }

    @Blocking
    public static List<ITemplateAnalyzer> discoverTemplateAnalyzers(IProject project, Set<Language> languages) {
        return discoverTemplates(project, languages).stream()
                .map(t -> (ITemplateAnalyzer) t)
                .toList();
    }

    public static Path getStoragePath(FrameworkTemplate template, IProject project) {
        return template.getStoragePath(project);
    }

    @Blocking
    public static void saveTemplateAnalyzerState(
            FrameworkTemplate template, IProject project, List<TemplateAnalysisResult> results) {
        TreeSitterStateIO.saveTemplateState(results, getStoragePath(template, project));
    }

    @Blocking
    public static List<TemplateAnalysisResult> loadTemplateAnalyzerState(FrameworkTemplate template, IProject project) {
        return TreeSitterStateIO.loadTemplateState(getStoragePath(template, project));
    }
}
