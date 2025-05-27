package io.github.jbellis.brokk.analyzer;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Abstraction for a filename relative to the repo.  This exists to make it less difficult to ensure
 * that different filename objects can be meaningfully compared, unlike bare Paths which may
 * or may not be absolute, or may be relative to the jvm root rather than the repo root.
 */
public class ProjectFile implements BrokkFile {
    private static final long serialVersionUID = 1L;
    private transient Path root;
    private transient Path relPath;

    /**
     * root must be pre-normalized; we will normalize relPath if it is not already
     */
    public ProjectFile(Path root, Path relPath) {
        // We can't rely on these being set until after deserialization
        if (root != null && relPath != null) {
            if (!root.isAbsolute()) {
                throw new IllegalArgumentException("Root must be absolute, got " + root);
            }
            if (!root.equals(root.normalize())) {
                throw new IllegalArgumentException("Root must be normalized, got " + root);
            }
            if (relPath.isAbsolute()) {
                throw new IllegalArgumentException("RelPath must be relative, got " + relPath);
            }
            relPath = relPath.normalize();
        }
        this.root = root;
        this.relPath = relPath;
    }

    public ProjectFile(Path root, String relName) {
        this(root, Path.of(relName));
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
        if (!(o instanceof ProjectFile)) return false;
        ProjectFile projectFile = (ProjectFile) o;
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

    private void writeObject(ObjectOutputStream oos) throws IOException {
        oos.defaultWriteObject();
        // store the string forms of root/relPath
        oos.writeUTF(root.toString());
        oos.writeUTF(relPath.toString());
    }

    private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
        // read all non-transient fields
        ois.defaultReadObject();
        // reconstitute root/relPath from the strings
        String rootString = ois.readUTF();
        String relString = ois.readUTF();
        // both must be absolute/relative as before
        root = Path.of(rootString);
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException("Root must be absolute");
        }

        relPath = Path.of(relString);
        if (relPath.isAbsolute()) {
            throw new IllegalArgumentException("RelPath must be relative");
        }
    }
}
