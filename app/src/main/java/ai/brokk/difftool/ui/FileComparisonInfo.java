package ai.brokk.difftool.ui;

/**
 * Inner class to hold a single file comparison metadata
 */
public class FileComparisonInfo {
    public final BufferSource leftSource;
    public final BufferSource rightSource;

    public FileComparisonInfo(BufferSource leftSource, BufferSource rightSource) {
        this.leftSource = leftSource;
        this.rightSource = rightSource;
    }

    String getDisplayName() {
        // Returns formatted name for UI display
        String leftName = getSourceName(leftSource);
        String rightName = getSourceName(rightSource);

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
