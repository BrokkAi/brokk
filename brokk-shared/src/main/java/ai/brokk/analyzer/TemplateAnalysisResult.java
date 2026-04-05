package ai.brokk.analyzer;

import java.util.List;
import java.util.Set;

/**
 * Represents the results of a template analysis, including discovered symbols,
 * errors, or cross-references to the host language.
 */
public record TemplateAnalysisResult(
        String analyzerName, ProjectFile templateFile, Set<CodeUnit> discoveredUnits, List<String> errors) {

    public static TemplateAnalysisResult empty(String name, ProjectFile file) {
        return new TemplateAnalysisResult(name, file, Set.of(), List.of());
    }
}
