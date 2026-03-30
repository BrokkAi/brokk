package ai.brokk.analyzer;

import ai.brokk.IContextManager;
import ai.brokk.project.IProject;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Focuses on DSLs (Domain Specific Languages) that exist in the context of a Host language.
 * Designed to be triggered by a Host IAnalyzer through the MultiAnalyzer.
 */
public interface ITemplateAnalyzer {

    /**
     * Returns true if this template analyzer should be active for the given project.
     * This typically checks for framework-specific markers, dependencies, or configuration files.
     */
    boolean isApplicable(IProject project);

    /**
     * @return The human-readable name of the analyzer (e.g., "Angular").
     */
    String name();

    /**
     * @return The unique internal name of the analyzer (e.g., "ANGULAR").
     */
    String internalName();

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

    /**
     * Returns the current analysis state for persistence.
     */
    List<TemplateAnalysisResult> snapshotState();

    /**
     * Restores the analyzer state from a previously persisted snapshot.
     */
    void restoreState(List<TemplateAnalysisResult> state);

    /**
     * Returns the template sources associated with the given host class.
     *
     * @param hostClass The CodeUnit of the component/class in the host language.
     * @return A set of template source strings.
     */
    default Set<String> getTemplateSources(CodeUnit hostClass) {
        return Set.of();
    }

    /**
     * Returns the template files associated with the given host class.
     *
     * @param hostClass      The CodeUnit of the component/class in the host language.
     * @param contextManager The context manager used to resolve relative paths to ProjectFiles.
     * @return A set of template ProjectFiles.
     */
    default Set<ProjectFile> getTemplateFiles(CodeUnit hostClass, IContextManager contextManager) {
        return Set.of();
    }
}
