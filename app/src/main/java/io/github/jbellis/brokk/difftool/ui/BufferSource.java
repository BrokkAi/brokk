package io.github.jbellis.brokk.difftool.ui;

import java.io.File;
import org.jetbrains.annotations.Nullable;

public sealed interface BufferSource {
    String title();

    record FileSource(File file, String title) implements BufferSource {}

    /**
     * String-based buffer source with optional revision metadata for Git blame support.
     *
     * @param content The file content
     * @param title Display title (typically commit SHA or label)
     * @param filename The file path (relative or absolute)
     * @param revisionSha Optional Git revision SHA for blame lookups
     */
    record StringSource(String content, String title, @Nullable String filename, @Nullable String revisionSha)
            implements BufferSource {

        public StringSource(String content, String title) {
            this(content, title, null, null);
        }

        public StringSource(String content, String title, @Nullable String filename) {
            this(content, title, filename, null);
        }
    }
}
