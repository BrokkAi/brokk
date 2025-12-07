package ai.brokk.gui.changes;

import static java.util.Objects.requireNonNull;

/**
 * Small utilities to produce user-facing labels for changed files based on their classification.
 */
public final class ChangeLabelUtil {

    private ChangeLabelUtil() {
        throw new AssertionError("no instances");
    }

    /**
     * Returns a display label for the given path augmented with a suffix when the file is binary or too large.
     *
     * @param path   the file path to display (must be non-null)
     * @param status the classification of the file (must be non-null)
     * @return a human-friendly display label; never null
     */
    public static String makeDisplayLabel(String path, ChangeFileStatus status) {
        requireNonNull(path, "path");
        requireNonNull(status, "status");

        return switch (status) {
            case BINARY -> path + " (binary)";
            case OVERSIZED -> path + " (too large)";
            case TEXT -> path;
        };
    }
}
