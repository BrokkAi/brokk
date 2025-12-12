package ai.brokk.difftool.ui;

import ai.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

public sealed interface BufferSource {
    String title();

    /**
     * Display name for UI (filename or label).
     */
    default String displayName() {
        if (this instanceof FileSource fs) {
            return fs.file().getFileName();
        } else if (this instanceof StringSource ss) {
            return ss.filename() != null ? ss.filename() : ss.title();
        }
        return title();
    }

    /**
     * Filename or path hint for syntax detection.
     */
    @Nullable
    String filename();

    /**
     * Estimated size in bytes for preload validation.
     */
    long estimatedSizeBytes();

    /**
     * Read the full content of this source.
     *
     * @return the content as a string
     */
    @Blocking
    String content();

    /**
     * Whether this source represents a working tree (uncommitted) file.
     */
    default boolean isWorkingTreeSource() {
        return this instanceof FileSource;
    }

    /**
     * Whether this source has revision metadata for blame support.
     */
    default boolean hasRevisionMetadata() {
        return (this instanceof StringSource ss) && ss.revisionSha() != null;
    }

    @Nullable
    String revisionSha();

    /**
     * ProjectFile-based source for files on disk.
     *
     * @param file The file within the project
     * @param title Display title (typically filename or label)
     */
    record FileSource(ProjectFile file, String title) implements BufferSource {
        public FileSource(ProjectFile file) {
            this(file, file.toString());
        }

        @Override
        public String filename() {
            return file.getFileName();
        }

        @Override
        public long estimatedSizeBytes() {
            var jufile = file.absPath().toFile();
            if (jufile.exists() && jufile.isFile()) {
                return jufile.length();
            }
            return 0L;
        }

        @Override
        @Blocking
        public String content() {
            return file.read()
                    .orElseThrow(() -> new UncheckedIOException(
                            new IOException("Unable to read %s".formatted(file.getFileName()))));
        }

        @Override
        public @Nullable String revisionSha() {
            return null;
        }
    }

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

        @Override
        public long estimatedSizeBytes() {
            return content.getBytes(StandardCharsets.UTF_8).length;
        }
    }
}
