package ai.brokk.agents;

import ai.brokk.TaskResult.StopReason;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Generates shields.io badge markdown for task completion status.
 */
public final class StatusBadge {
    private StatusBadge() {}

    public static String badgeFor(StopReason reason) {
        return switch (reason) {
            case SUCCESS -> badge("success", "brightgreen");
            case INTERRUPTED -> badge("interrupted", "yellow");
            case READ_ONLY_EDIT, LLM_ABORTED, LLM_CONTEXT_SIZE, TURN_LIMIT -> badge(formatLabel(reason), "orange");
            case LLM_ERROR, PARSE_ERROR, APPLY_ERROR, BUILD_ERROR, IO_ERROR, TOOL_ERROR ->
                badge(formatLabel(reason), "red");
        };
    }

    private static String badge(String label, String color) {
        var encoded = URLEncoder.encode(label, StandardCharsets.UTF_8).replace("+", "%20");
        return "![Status](https://img.shields.io/badge/Status-%s-%s?style=flat)".formatted(encoded, color);
    }

    private static String formatLabel(StopReason reason) {
        return switch (reason) {
            case SUCCESS -> "success";
            case INTERRUPTED -> "interrupted";
            case LLM_ERROR -> "LLM error";
            case PARSE_ERROR -> "parse error";
            case APPLY_ERROR -> "apply error";
            case BUILD_ERROR -> "build error";
            case READ_ONLY_EDIT -> "read-only violation";
            case IO_ERROR -> "IO error";
            case LLM_ABORTED -> "LLM aborted";
            case TOOL_ERROR -> "tool error";
            case LLM_CONTEXT_SIZE -> "context overflow";
            case TURN_LIMIT -> "turn limit reached";
        };
    }
}
