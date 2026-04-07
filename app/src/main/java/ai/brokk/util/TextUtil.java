package ai.brokk.util;

import org.jetbrains.annotations.Nullable;

public final class TextUtil {
    private TextUtil() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static int countWords(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }
}
