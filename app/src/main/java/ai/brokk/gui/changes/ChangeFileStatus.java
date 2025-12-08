package ai.brokk.gui.changes;

/**
 * Status classification for files shown in the Review/Changes UI.
 */
public enum ChangeFileStatus {
    /**
     * Plain text file suitable for diffing.
     */
    TEXT,

    /**
     * Binary or non-text file; textual diffing should be avoided.
     */
    BINARY,

    /**
     * File whose content is too large to reasonably diff; avoid expensive diffing and mark as oversized.
     */
    OVERSIZED
}
