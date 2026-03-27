package ai.brokk.analyzer;

import java.util.List;
import java.util.Map;

/**
 * Focuses on DSLs (Domain Specific Languages) that exist in the context of a Host language.
 * Designed to be triggered by a Host IAnalyzer through the MultiAnalyzer.
 */
public interface ITemplateAnalyzer {

    /**
     * @return The unique name of the analyzer (e.g., "AngularTemplateAnalyzer").
     */
    String getName();

    /**
     * @return A list of file extensions this analyzer handles (e.g., ".html", ".template").
     */
    List<String> getSupportedExtensions();

    /**
     * Responds to signals emitted by a host analyzer.
     *
     * @param signal The event type (e.g., "COMPONENT_FOUND")
     * @param payload Includes context-specific data like the host CodeUnit and template ProjectFile
     * @param globalState The full AnalyzerState snapshot for cross-referencing
     */
    void onHostSignal(String signal, Map<String, Object> payload, TreeSitterAnalyzer.AnalyzerState globalState);

    /**
     * Performs analysis on a template file associated with a specific host class.
     *
     * @param hostAnalyzer The IAnalyzer that provides context for the host language (e.g., TypeScriptAnalyzer)
     * @param templateFile The template file to analyze
     * @param hostClass    The CodeUnit of the component/class in the host language
     * @return Results to be merged into the final project report
     */
    TemplateAnalysisResult analyzeTemplate(IAnalyzer hostAnalyzer, ProjectFile templateFile, CodeUnit hostClass);
}
