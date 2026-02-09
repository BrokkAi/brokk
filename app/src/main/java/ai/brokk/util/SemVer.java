package ai.brokk.util;

import org.jetbrains.annotations.Nullable;

/**
 * Represents a Semantic Version (MAJOR.MINOR.PATCH).
 */
public record SemVer(int major, int minor, int patch) {

    /**
     * Parses a SemVer string. Returns null if the string is not a valid 3-part SemVer.
     */
    public static @Nullable SemVer parse(@Nullable String version) {
        if (version == null) {
            return null;
        }
        String[] parts = version.split("\\.", -1);
        if (parts.length != 3) {
            return null;
        }
        try {
            return new SemVer(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "%d.%d.%d".formatted(major, minor, patch);
    }
}
