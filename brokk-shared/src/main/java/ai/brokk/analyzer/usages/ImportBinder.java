package ai.brokk.analyzer.usages;

import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Language-agnostic representation of local binding names introduced by imports.
 *
 * <p>For JS/TS v1 this models ESM bindings only; CommonJS is a follow-up.
 */
public record ImportBinder(Map<String, ImportBinding> bindings) {

    public static ImportBinder empty() {
        return new ImportBinder(Map.of());
    }

    public record ImportBinding(String moduleSpecifier, ImportKind kind, @Nullable String importedName) {}

    public enum ImportKind {
        DEFAULT,
        NAMED,
        NAMESPACE
    }
}
