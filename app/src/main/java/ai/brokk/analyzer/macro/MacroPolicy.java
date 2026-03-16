package ai.brokk.analyzer.macro;

import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public record MacroPolicy(String version, String language, List<MacroMatch> macros) {

    public record MacroMatch(
            String name, @Nullable String path, MacroStrategy strategy, @Nullable Map<String, Object> options) {}

    public enum MacroStrategy {
        BYPASS,
        AI_EXPAND,
        BUILTIN
    }
}
