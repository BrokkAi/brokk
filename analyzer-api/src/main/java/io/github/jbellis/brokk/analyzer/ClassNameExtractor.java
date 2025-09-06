package io.github.jbellis.brokk.analyzer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Utility for extracting and normalizing class/module names from textual references such as "MyClass.myMethod" or
 * "ns::Type::method".
 *
 * <p>This utility centralizes the simple, heuristic patterns used across analyzers. It is intentionally conservative:
 * it prefers returning {@link Optional#empty()} when the input does not look like a plausible method/member reference.
 *
 * <p>Normalization helpers are provided to produce several lookup-friendly variants (strip templates, replace "::" with
 * ".", etc.) that are useful when trying to resolve the extracted token against an index.
 */
public final class ClassNameExtractor {

    private ClassNameExtractor() {}

    /* Java heuristics ----------------------------------------------------- */

    public static Optional<String> extractForJava(String reference) {
        if (reference == null) return Optional.empty();
        var trimmed = reference.trim();
        if (trimmed.isEmpty() || !trimmed.contains(".")) return Optional.empty();

        // Find the last dot that's not inside parentheses
        var lastDot = -1;
        var parenDepth = 0;
        for (int i = trimmed.length() - 1; i >= 0; i--) {
            char c = trimmed.charAt(i);
            if (c == ')') {
                parenDepth++;
            } else if (c == '(') {
                parenDepth--;
            } else if (c == '.' && parenDepth == 0) {
                lastDot = i;
                break;
            }
        }

        if (lastDot <= 0 || lastDot >= trimmed.length() - 1) return Optional.empty();

        var lastPart = trimmed.substring(lastDot + 1);
        var beforeLast = trimmed.substring(0, lastDot);

        // Method name heuristic: common Java method starts with lowercase or underscore
        // Now also supports method calls with parameters like "runOnEdt(...)" or "runOnEdt(task)"
        if (!lastPart.matches("[a-z_][a-zA-Z0-9_]*(?:\\([^)]*\\))?")) return Optional.empty();

        // Class segment heuristic: rightmost segment should look like a PascalCase identifier
        var segLastDot = beforeLast.lastIndexOf('.');
        var lastSegment = segLastDot >= 0 ? beforeLast.substring(segLastDot + 1) : beforeLast;
        if (!lastSegment.matches("[A-Z][a-zA-Z0-9_]*")) return Optional.empty();

        return Optional.of(beforeLast);
    }

    /* Python heuristics --------------------------------------------------- */

    public static Optional<String> extractForPython(String reference) {
        if (reference == null) return Optional.empty();
        var trimmed = reference.trim();
        if (trimmed.isEmpty() || !trimmed.contains(".")) return Optional.empty();

        var lastDot = trimmed.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == trimmed.length() - 1) return Optional.empty();

        var lastPart = trimmed.substring(lastDot + 1);
        var beforeLast = trimmed.substring(0, lastDot);

        if (!lastPart.matches("[a-zA-Z_][a-zA-Z0-9_]*")) return Optional.empty();
        if (!beforeLast.matches("[a-zA-Z_.][a-zA-Z0-9_.]*")) return Optional.empty();

        return Optional.of(beforeLast);
    }

    /* C++ heuristics ------------------------------------------------------ */

    public static Optional<String> extractForCpp(String reference) {
        if (reference == null) return Optional.empty();
        var trimmed = reference.trim();
        if (trimmed.isEmpty() || !trimmed.contains("::")) return Optional.empty();

        var lastDoubleColon = trimmed.lastIndexOf("::");
        if (lastDoubleColon <= 0 || lastDoubleColon >= trimmed.length() - 2) return Optional.empty();

        var lastPart = trimmed.substring(lastDoubleColon + 2);
        var beforeLast = trimmed.substring(0, lastDoubleColon);

        if (!lastPart.matches("[a-zA-Z_][a-zA-Z0-9_]*")) return Optional.empty();
        if (!beforeLast.matches("[a-zA-Z_:][a-zA-Z0-9_:]*")) return Optional.empty();

        return Optional.of(beforeLast);
    }

    /* Rust heuristics ----------------------------------------------------- */

    public static Optional<String> extractForRust(String reference) {
        // Rust uses :: like C++
        return extractForCpp(reference);
    }

    /* Normalization helpers ----------------------------------------------- */

    /**
     * Produce a list of candidate normalized variants for a previously-extracted class name. The list preserves order
     * and deduplicates while keeping insertion order.
     *
     * <p>Examples: - "std::vector" -> ["std::vector", "std.vector"] - "com.example.MyClass" -> ["com.example.MyClass",
     * "com::example::MyClass"] - "crate::mod::Type<T>" -> ["crate::mod::Type<T>", "crate::mod::Type", "crate.mod.Type"]
     *
     * <p>Note: This helper performs lightweight normalization only (template parameter stripping, simple separator
     * swaps). More advanced canonicalization should be performed by callers if required.
     */
    public static List<String> normalizeVariants(String extracted) {
        var variants = new LinkedHashSet<String>();
        if (extracted == null || extracted.isBlank()) return List.of();

        variants.add(extracted);

        // Strip simple template arguments like <...>
        var strippedTemplates = extracted.replaceAll("<.*?>", "");
        if (!strippedTemplates.equals(extracted)) variants.add(strippedTemplates);

        // Separator variants
        if (extracted.contains("::")) variants.add(extracted.replace("::", "."));
        if (extracted.contains(".")) variants.add(extracted.replace(".", "::"));

        // Also add stripped-templates + swapped-separators
        if (!strippedTemplates.equals(extracted)) {
            if (strippedTemplates.contains("::")) variants.add(strippedTemplates.replace("::", "."));
            if (strippedTemplates.contains(".")) variants.add(strippedTemplates.replace(".", "::"));
        }

        return new ArrayList<>(variants);
    }
}
