package ai.brokk.analyzer.macro;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public record MacroPolicy(String version, String language, List<MacroMatch> macros) {

    public record MacroMatch(
            @JsonProperty("name") String name,
            @JsonProperty("parent") @Nullable String parent,
            @JsonProperty("scope") @Nullable MacroScope scope,
            @JsonProperty("strategy") MacroStrategy strategy,
            @JsonTypeInfo(
                            use = JsonTypeInfo.Id.NAME,
                            include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
                            property = "strategy",
                            defaultImpl = BypassConfig.class)
                    @JsonSubTypes({
                        @JsonSubTypes.Type(value = TemplateConfig.class, name = "TEMPLATE"),
                        @JsonSubTypes.Type(value = BypassConfig.class, name = "BYPASS")
                    })
                    @JsonProperty("options")
                    MacroConfig options) {

        public MacroMatch {
            scope = (scope != null) ? scope : MacroScope.LIBRARY;
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
         * Use a Mustache template provided in the policy options for expansion.
         */
        TEMPLATE
    }

    public sealed interface MacroConfig permits TemplateConfig, BypassConfig {}

    public record TemplateConfig(String template) implements MacroConfig {}

    public record BypassConfig() implements MacroConfig {}
}
