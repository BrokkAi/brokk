package ai.brokk.tools;

import java.util.Collection;
import java.util.Map;

/**
 * Renders explanations and YAML blocks for tool outputs and pseudo-tool outputs.
 * Provides consistent formatting for both real tools and internal operations.
 */
public class ExplanationRenderer {

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
