package ai.brokk.analyzer.macro;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public record MacroPolicy(String version, String language, List<MacroMatch> macros) {

    public record MacroMatch(
            @JsonProperty("name") String name,
            @JsonProperty("scope") @Nullable MacroScope scope,
            @JsonProperty("strategy") MacroStrategy strategy,
            @JsonTypeInfo(
                            use = JsonTypeInfo.Id.NAME,
                            include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
                            property = "strategy",
                            defaultImpl = BypassConfig.class)
                    @JsonSubTypes({
                        @JsonSubTypes.Type(value = AIExpandConfig.class, name = "AI_EXPAND"),
                        @JsonSubTypes.Type(value = TemplateConfig.class, name = "TEMPLATE"),
                        @JsonSubTypes.Type(value = BuiltinConfig.class, name = "BUILTIN"),
                        @JsonSubTypes.Type(value = BypassConfig.class, name = "BYPASS")
                    })
                    @JsonProperty("options")
                    @Nullable
                    MacroConfig options) {
        public MacroMatch {
            if (scope == null) {
                scope = MacroScope.LIBRARY;
            }
        }
    }

    public enum MacroScope {
        APPLICATION,
        LIBRARY
    }

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

    public sealed interface MacroConfig permits AIExpandConfig, TemplateConfig, BuiltinConfig, BypassConfig {}

    public record AIExpandConfig(@Nullable Integer max_tokens, @Nullable String prompt_hint) implements MacroConfig {}

    public record TemplateConfig(String template) implements MacroConfig {}

    public record BuiltinConfig() implements MacroConfig {}

    public record BypassConfig() implements MacroConfig {}
}
