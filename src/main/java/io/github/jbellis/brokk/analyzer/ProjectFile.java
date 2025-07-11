package io.github.jbellis.brokk.analyzer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonGetter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Abstraction for a filename relative to the repo.  This exists to make it less difficult to ensure
 * that different filename objects can be meaningfully compared, unlike bare Paths which may
 * or may not be absolute, or may be relative to the jvm root rather than the repo root.
 */
public class ProjectFile implements BrokkFile {
    private final transient Path root;
    private final transient Path relPath;

    /**
     * root must be pre-normalized; we will normalize relPath if it is not already
     */
    @JsonCreator
    public ProjectFile(@JsonProperty("root") Path root, @JsonProperty("relPath") Path relPath) {
        // Validation and normalization
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException("Root must be absolute, got " + root);
        }
        if (!root.equals(root.normalize())) {
            throw new IllegalArgumentException("Root must be normalized, got " + root);
        }
        if (relPath.isAbsolute()) {
            throw new IllegalArgumentException("RelPath must be relative, got " + relPath);
        }
        
        this.root = root;
        this.relPath = relPath.normalize();
    }

    public ProjectFile(Path root, String relName) {
        this(root, Path.of(relName));
    }

    // JsonGetter methods for Jackson serialization since fields are transient
    @JsonGetter("root")
    public Path getRoot() {
        return root;
    }

    @JsonGetter("relPath") 
    public Path getRelPath() {
        return relPath;
    }


    @Override
    public Path absPath() {
        return root.resolve(relPath);
    }

    public void create() throws IOException {
        Files.createDirectories(absPath().getParent());
        Files.createFile(absPath());
    }

    public void write(String st) throws IOException {
        Files.createDirectories(absPath().getParent());
        Files.writeString(absPath(), st);
    }

    /**
     * Also relative (but unlike raw Path.getParent, ours returns empty path instead of null)
     */
    @JsonIgnore
    public Path getParent() {
        // since this is the *relative* path component I think it's more correct to return empty than null;
        // the other alternative is to wrap in Optional, but then comparing with an empty path is messier
        var p = relPath.getParent();
        return p == null ? Path.of("") : p;
    }

    @Override
    public String toString() {
        return relPath.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProjectFile projectFile)) return false;
        return Objects.equals(root, projectFile.root) &&
               Objects.equals(relPath, projectFile.relPath);
    }

    @Override
    public int hashCode() {
        return relPath.hashCode();
    }

    public Language getLanguage() {
        return Language.fromExtension(extension());
    }
}
