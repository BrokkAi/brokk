package io.github.jbellis.brokk.context;

import io.github.jbellis.brokk.util.ComputedValue;
import org.jetbrains.annotations.Nullable;

/**
 * Non-breaking dynamic accessors for fragments that may compute values asynchronously.
 * Default adapters should provide completed values based on current state so legacy
 * call sites keep working without changes.
 */
public interface DynamicFragment {
    ComputedValue<String> computedText();
    ComputedValue<String> computedDescription();
    ComputedValue<String> computedSyntaxStyle();

    /**
     * Optionally provide computed image payload; default is null for non-image fragments.
     */
    default @Nullable ComputedValue<byte[]> computedImageBytes() {
        return null;
    }

    /**
     * Return a copy with cleared ComputedValues; identity (id) is preserved by default.
     * Implementations that track external state may override to trigger recomputation.
     */
    default ContextFragment refreshCopy() {
        return (ContextFragment) this;
    }
}
