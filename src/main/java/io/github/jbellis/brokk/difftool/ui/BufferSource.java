package io.github.jbellis.brokk.difftool.ui;

import java.io.File;
import java.util.Objects;

public sealed interface BufferSource {
    String title();

    record FileSource(File file, String title) implements BufferSource {
        public FileSource {
            Objects.requireNonNull(file, "file cannot be null");
            Objects.requireNonNull(title, "title cannot be null");
        }
    }

    record StringSource(String content, String title, String filename) implements BufferSource {
        public StringSource {
            Objects.requireNonNull(content, "content cannot be null"); // Empty string is allowed
            Objects.requireNonNull(title, "title cannot be null");
            // filename can be null
        }

        public StringSource(String content, String title) {
            this(content, title, null);
        }
    }
}
