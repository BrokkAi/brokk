package io.github.jbellis.brokk.analyzer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonGetter;
import java.nio.file.Path;

/**
 * A BrokkFile that represents a file not relative to any repo, just specified by its absolute path.
 */
public class ExternalFile implements BrokkFile {
    private final transient Path path;

    // Constructor validation
    @JsonCreator
    public ExternalFile(@JsonProperty("path") Path path) {
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("Path must be absolute");
        }
        if (!path.equals(path.normalize())) {
            throw new IllegalArgumentException("Path must be normalized");
        }
        this.path = path;
    }


    @Override
    public Path absPath() {
        return path;
    }

    // JsonGetter method for Jackson serialization since field is transient
    @JsonGetter("path")
    public Path getPath() {
        return path;
    }

    @Override
    public String toString() {
        return path.toString();
    }
}
