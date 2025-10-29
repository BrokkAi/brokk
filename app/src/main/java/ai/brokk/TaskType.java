package ai.brokk;

import java.util.Locale;
import java.util.Optional;

public enum TaskType {
    NONE,
    ARCHITECT,
    CODE,
    ASK,
    SEARCH,
    CONTEXT,
    MERGE,
    BLITZFORGE;

    public String displayName() {
        if (this == SEARCH) {
            return "Lutz Mode";
        }
        var lower = name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    public static Optional<TaskType> safeParse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        var s = value.trim();
        if (s.isEmpty()) {
            return Optional.empty();
        }

        for (var t : values()) {
            if (t.name().equalsIgnoreCase(s)) {
                return Optional.of(t);
            }
        }
        for (var t : values()) {
            if (t.displayName().equalsIgnoreCase(s)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
