package ai.brokk.difftool.ui;

import ai.brokk.analyzer.ProjectFile;
import org.jetbrains.annotations.Nullable;

/**
 * Metadata for a single file comparison in the diff tool.
 */
public record FileComparisonInfo(
        @Nullable ProjectFile file,
        BufferSource leftSource,
        BufferSource rightSource) {

    public String getDisplayName() {
        // Returns formatted name for UI display
        String leftName = getSourceName(leftSource());
        String rightName = getSourceName(rightSource());

        if (leftName.equals(rightName)) {
            return leftName;
        }
        return leftName + " vs " + rightName;
    }

    private String getSourceName(BufferSource source) {
        if (source instanceof BufferSource.FileSource fs) {
            return fs.file().getFileName();
        } else if (source instanceof BufferSource.StringSource ss) {
            return ss.filename() != null ? ss.filename() : ss.title();
        }
        return source.title();
    }
}
