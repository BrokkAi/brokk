package ai.brokk.executor.agents;

import java.util.List;
import org.jetbrains.annotations.Nullable;

final class SchemaFailureDiagnostics {
    private SchemaFailureDiagnostics() {}

    static @Nullable String validationError(String text) {
        var finalValidation = value(text, "finalValidation");
        if (hasDiagnosticValue(finalValidation)) {
            return finalValidation;
        }
        var originalValidation = value(text, "originalValidation");
        if (hasDiagnosticValue(originalValidation)) {
            return originalValidation;
        }
        return value(text, "validation");
    }

    static @Nullable String invalidOutputExcerpt(String text) {
        return value(text, "invalidOutputExcerpt");
    }

    static @Nullable String originalOutputExcerpt(String text) {
        return value(text, "originalOutputExcerpt");
    }

    static @Nullable String finishReason(String text) {
        return value(text, "finishReason");
    }

    static @Nullable String value(String text, String key) {
        var prefix = key + "=";
        var start = findDiagnosticKeyStart(text, key);
        if (start < 0) {
            return null;
        }
        var valueStart = start + prefix.length();
        var nextKeyStart = nextDiagnosticKeyStart(text, valueStart);
        if (nextKeyStart < 0) {
            return text.substring(valueStart).strip();
        }
        return text.substring(valueStart, nextKeyStart).strip();
    }

    private static boolean hasDiagnosticValue(@Nullable String value) {
        return value != null && !value.isBlank() && !"null".equals(value);
    }

    private static int findDiagnosticKeyStart(String text, String key) {
        var prefix = key + "=";
        var start = text.indexOf(prefix);
        while (start >= 0) {
            if (start == 0 || Character.isWhitespace(text.charAt(start - 1))) {
                return start;
            }
            start = text.indexOf(prefix, start + prefix.length());
        }
        return -1;
    }

    private static int nextDiagnosticKeyStart(String text, int valueStart) {
        return diagnosticKeys().stream()
                .flatMap(key -> List.of(" " + key + "=", "\n" + key + "=").stream())
                .mapToInt(key -> text.indexOf(key, valueStart))
                .filter(index -> index >= 0)
                .min()
                .orElse(-1);
    }

    private static List<String> diagnosticKeys() {
        return List.of(
                "schema",
                "candidateSource",
                "originalValidation",
                "finalValidation",
                "validation",
                "attempts",
                "finishReason",
                "initialInvalidOutputExcerpt",
                "originalOutputExcerpt",
                "invalidOutputExcerpt",
                "finalValidationError",
                "llmRepairAttempted",
                "deterministicRepairAttempted",
                "deterministicChanges",
                "salvageAttempted",
                "salvageChanges");
    }
}
