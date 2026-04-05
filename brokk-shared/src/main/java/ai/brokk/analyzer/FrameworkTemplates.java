package ai.brokk.analyzer;

import ai.brokk.analyzer.frameworks.AngularTemplateAnalyzer;
import ai.brokk.project.ICoreProject;
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
    public static List<FrameworkTemplate> discoverTemplates(ICoreProject project, Set<Language> languages) {
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
    public static List<ITemplateAnalyzer> discoverTemplateAnalyzers(ICoreProject project, Set<Language> languages) {
        return discoverTemplates(project, languages).stream()
                .map(t -> (ITemplateAnalyzer) t)
                .toList();
    }

    public static Path getStoragePath(FrameworkTemplate template, ICoreProject project) {
        return template.getStoragePath(project.getRoot());
    }

    @Blocking
    public static void saveTemplateAnalyzerState(
            FrameworkTemplate template, ICoreProject project, List<TemplateAnalysisResult> results) {
        TreeSitterStateIO.saveTemplateState(results, getStoragePath(template, project));
    }

    @Blocking
    public static List<TemplateAnalysisResult> loadTemplateAnalyzerState(
            FrameworkTemplate template, ICoreProject project) {
        return TreeSitterStateIO.loadTemplateState(getStoragePath(template, project));
    }
}
