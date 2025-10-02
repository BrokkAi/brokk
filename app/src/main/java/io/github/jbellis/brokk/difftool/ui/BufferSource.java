package io.github.jbellis.brokk.difftool.ui;

import java.io.File;
import org.jetbrains.annotations.Nullable;

public sealed interface BufferSource {
    String title();

    record FileSource(File file, String title) implements BufferSource {}

    record StringSource(String content, String title, @Nullable String filename) implements BufferSource {

        public StringSource(String content, String title) {
            this(content, title, null);
        }
    }
}
