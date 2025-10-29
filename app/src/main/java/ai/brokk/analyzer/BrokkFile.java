package ai.brokk.analyzer;

import ai.brokk.util.SyntaxDetector;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

public interface BrokkFile extends Comparable<BrokkFile> {
    /** Heuristic binary detection: presence of NUL within the first few KB. */
    static boolean isBinary(String content) {
        int limit = Math.min(content.length(), 8192);
        for (int i = 0; i < limit; i++) {
            if (content.charAt(i) == '\0') return true;
        }
        return false;
    }

    Path absPath();

    default Optional<String> read() {
        try {
            return Optional.of(Files.readString(absPath()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    default boolean exists() {
        return Files.exists(absPath());
    }

    /** best guess as to whether a file is text and hence eligible for substring search */
    @JsonIgnore
    default boolean isText() {
        try {
            var path = absPath();
            var isNotImage =
                    FileExtensions.IMAGE_EXTENSIONS.stream().noneMatch(ext -> FileExtensions.matches(path, ext));
            var hasTextExtension =
                    FileExtensions.TEXT_EXTENSIONS.stream().anyMatch(ext -> FileExtensions.matches(path, ext));
            var isSmallFile = Files.exists(path) && Files.isRegularFile(path) && Files.size(path) < 128 * 1024;

            return isNotImage && (hasTextExtension || isSmallFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Just the filename, no path at all */
    @JsonIgnore
    default String getFileName() {
        return absPath().getFileName().toString();
    }

    @Override
    String toString();

    @Override
    default int compareTo(BrokkFile o) {
        return absPath().compareTo(o.absPath());
    }

    @JsonIgnore
    default long mtime() throws IOException {
        return Files.getLastModifiedTime(absPath()).toMillis();
    }

    /** return the (lowercased) extension [not including the dot] */
    @JsonIgnore
    default String extension() {
        var filename = toString();
        int lastDot = filename.lastIndexOf('.');
        // Ensure dot is not the first character and is not the last character
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        }
        return ""; // No extension found or invalid placement
    }

    default String getSyntaxStyle() {
        return SyntaxDetector.fromExtension(extension());
    }
}
