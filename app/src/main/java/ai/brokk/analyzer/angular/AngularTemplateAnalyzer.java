package ai.brokk.analyzer.angular;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ITemplateAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TemplateAnalysisResult;
import ai.brokk.analyzer.TreeSitterAnalyzer;
import ai.brokk.project.IProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

/**
 * Template analyzer for Angular applications.
 * Detects Angular components and analyzes their HTML templates.
 */
@NullMarked
public class AngularTemplateAnalyzer implements ITemplateAnalyzer {
    private static final Logger log = LogManager.getLogger(AngularTemplateAnalyzer.class);

    @Override
    public boolean isApplicable(IProject project) {
        Path root = project.getRoot();

        // Check for configuration files up to 5 levels deep
        try (Stream<Path> stream = Files.walk(root, 5)) {
            boolean hasConfig = stream.filter(Files::isRegularFile)
                    .anyMatch(p -> {
                        String fileName = p.getFileName().toString();
                        if (fileName.equals("angular.json")) {
                            return true;
                        }
                        if (fileName.equals("package.json")) {
                            try {
                                String content = Files.readString(p);
                                return content.contains("@angular/core");
                            } catch (IOException e) {
                                return false;
                            }
                        }
                        return false;
                    });
            if (hasConfig) return true;
        } catch (IOException e) {
            log.debug("Error scanning for Angular config in {}: {}", root, e.getMessage());
        }

        // Check for component templates
        return project.getAllFiles().stream()
                .anyMatch(pf -> pf.getFileName().endsWith(".component.html"));
    }

    @Override
    public String getName() {
        return "AngularTemplateAnalyzer";
    }

    @Override
    public List<String> getSupportedExtensions() {
        return List.of("html");
    }

    @Override
    public void onHostSignal(String signal, Map<String, Object> payload, TreeSitterAnalyzer.AnalyzerState globalState) {
        // Implementation for responding to @Component discovery will go here
    }

    @Override
    public TemplateAnalysisResult analyzeTemplate(IAnalyzer hostAnalyzer, ProjectFile templateFile, CodeUnit hostClass) {
        // Focus of this PR is infrastructure; returns empty result for now.
        return TemplateAnalysisResult.empty(getName(), templateFile);
    }

    @Override
    public List<TemplateAnalysisResult> snapshotState() {
        return new ArrayList<>();
    }

    @Override
    public void restoreState(List<TemplateAnalysisResult> state) {
        // No-op for now
    }
}
