package ai.brokk.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Renders explanations and YAML blocks for tool outputs and pseudo-tool outputs.
 * Provides consistent formatting for both real tools and internal operations.
 */
public class ExplanationRenderer {
    private static final Logger logger = LogManager.getLogger(ExplanationRenderer.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Map<String, String> HEADLINES = Map.ofEntries(
            Map.entry("searchSymbols", "Searching for symbols"),
            Map.entry("getSymbolLocations", "Finding files for symbols"),
            Map.entry("searchSubstrings", "Searching for substrings"),
            Map.entry("searchFilenames", "Searching for filenames"),
            Map.entry("getFileContents", "Getting file contents"),
            Map.entry("getFileSummaries", "Getting file summaries"),
            Map.entry("getUsages", "Finding usages"),
            Map.entry("getRelatedClasses", "Finding related code"),
            Map.entry("getClassSkeletons", "Getting class overview"),
            Map.entry("getClassSources", "Fetching class source"),
            Map.entry("getMethodSources", "Fetching method source"),
            Map.entry("getCallGraphTo", "Getting call graph TO"),
            Map.entry("getCallGraphFrom", "Getting call graph FROM"),
            Map.entry("searchGitCommitMessages", "Searching git commits"),
            Map.entry("listFiles", "Listing files"),
            Map.entry("addFilesToWorkspace", "Adding files to workspace"),
            Map.entry("addClassesToWorkspace", "Adding classes to workspace"),
            Map.entry("addUrlContentsToWorkspace", "Adding URL contents to workspace"),
            Map.entry("appendNote", "Appending note"),
            Map.entry("addSymbolUsagesToWorkspace", "Adding symbol usages to workspace"),
            Map.entry("addClassSummariesToWorkspace", "Adding class summaries to workspace"),
            Map.entry("addFileSummariesToWorkspace", "Adding file summaries to workspace"),
            Map.entry("addMethodsToWorkspace", "Adding method sources to workspace"),
            Map.entry("dropWorkspaceFragments", "Removing from workspace"),
            Map.entry("recommendContext", "Recommending context"),
            Map.entry("createOrReplaceTaskList", "Creating or replacing task list"),
            Map.entry("callCodeAgent", "Calling code agent"),
            Map.entry("performedInitialReview", "Performed initial review"));

    public static String headlineFor(String toolName) {
        return HEADLINES.getOrDefault(toolName, toolName);
    }

    public static String renderToolRequest(ToolExecutionRequest request) {
        if (request.name().equals("answerSearch") || request.name().equals("abortSearch")) {
            return "";
        }

        try {
            Map<String, Object> argsMap =
                    OBJECT_MAPPER.readValue(request.arguments(), new TypeReference<LinkedHashMap<String, Object>>() {});
            return renderExplanation(headlineFor(request.name()), argsMap);
        } catch (Exception e) {
            logger.debug(
                    "Could not render explanation for tool request '{}' due to invalid JSON args: {}",
                    request.name(),
                    e.getMessage());
            return "";
        }
    }

    /**
     * Renders a headline with a YAML block of arguments/details.
     *
     * @param headline Human-readable description (e.g., "Adding files to workspace")
     * @param details Map of field names to values to render in YAML format
     * @return Formatted explanation string suitable for llmOutput
     */
    public static String renderExplanation(String headline, Map<String, Object> details) {
        var yaml = toYaml(details);
        return """
                   `%s`
                   ````yaml
                   %s
                   ````
                   """
                .formatted(headline, yaml);
    }

    /**
     * Converts a map to a YAML-like string representation.
     * Lists are rendered as bulleted items; multi-line strings are rendered as folded blocks.
     *
     * @param details Map to render
     * @return YAML-formatted string
     */
    private static String toYaml(Map<String, Object> details) {
        var sb = new StringBuilder();
        for (var entry : details.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            if (value instanceof Collection<?> list) {
                sb.append(key).append(":\n");
                for (var item : list) {
                    sb.append("  - ").append(item).append("\n");
                }
            } else if (value instanceof String s && s.contains("\n")) {
                sb.append(key).append(": |\n");
                s.lines().forEach(line -> sb.append("  ").append(line).append("\n"));
            } else {
                sb.append(key).append(": ").append(value).append("\n");
            }
        }
        return sb.toString();
    }
}
