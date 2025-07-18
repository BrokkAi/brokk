package io.github.jbellis.brokk.analyzer;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.jbellis.brokk.util.SyntaxDetector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public interface BrokkFile extends Comparable<BrokkFile> {
    Path absPath();

    default String read() throws IOException {
        return Files.readString(absPath());
    }

    default boolean exists() {
        return Files.exists(absPath());
    }

    /** best guess as to whether a file is text and hence eligible for substring search */
    @JsonIgnore
    default boolean isText() {
        try {
            return Files.isRegularFile(absPath())
                    && FileExtensions.IMAGE_EXTENSIONS.stream().noneMatch(ext -> FileExtensions.matches(absPath(), ext))
                    && (FileExtensions.TEXT_EXTENSIONS.stream().anyMatch(ext -> FileExtensions.matches(absPath(), ext))
                        || Files.size(absPath()) < 128 * 1024);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Just the filename, no path at all
     */
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
