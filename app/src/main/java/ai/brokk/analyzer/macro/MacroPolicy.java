package ai.brokk.analyzer.macro;

import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public record MacroPolicy(String version, String language, List<MacroMatch> macros) {

    public record MacroMatch(
            String name, @Nullable String path, MacroStrategy strategy, @Nullable Map<String, Object> options) {}

    /**
     * Defines the strategy used to handle macro expansion.
     */
    public enum MacroStrategy {
        /**
         * Do not expand the macro; it will be treated as an opaque call.
         */
        BYPASS,

        /**
         * Use an LLM to generate code definitions from the macro call site.
         */
        AI_EXPAND,

        /**
         * Use a pre-defined template (e.g., Mustache) for expansion.
         */
        BUILTIN,

        /**
         * Use a Mustache template provided in the policy options for expansion.
         */
        TEMPLATE
    }
}
