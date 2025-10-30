package ai.brokk.analyzer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Abstraction for a filename relative to the repo. This exists to make it less difficult to ensure that different
 * filename objects can be meaningfully compared, unlike bare Paths which may or may not be absolute, or may be relative
 * to the jvm root rather than the repo root.
 */
public class ProjectFile implements BrokkFile {
    private final transient Path root;
    private final transient Path relPath;

    /**
     * Public constructor for programmatic use only.
     *
     * <p>This constructor is intentionally lenient: it accepts relative roots and
     * normalizes the provided paths for convenience when constructing
     * {@code ProjectFile} instances programmatically (for example, in tests or
     * platform-specific code).</p>
     *
     * <p><strong>Important:</strong> this constructor is NOT intended to be used
     * by Jackson for JSON deserialization. JSON deserialization MUST go through the
     * strict {@link #forJson(Path, Path)} static factory (annotated with
     * {@code @JsonCreator}) which enforces that the JSON-supplied {@code root} is
     * absolute and applies the necessary validation rules.</p>
     *
     * <p>Do NOT add {@code @JsonProperty} annotations to this constructor's
     * parameters — doing so would cause Jackson to treat this constructor as a
     * creator, potentially selecting it over {@code forJson} and thereby bypassing
     * the validation performed by {@link #forJson(Path, Path)}.</p>
     */
    public ProjectFile(Path root, Path relPath) {
        if (relPath.toString().contains("%s")) {
            throw new IllegalArgumentException("RelPath %s contains interpolation markers".formatted(relPath));
        }

        // Accept relative roots (e.g., platform- or test-provided paths) by converting to an absolute,
        // normalized root. This keeps the constructor robust across platforms (Windows test fixtures that
        // use backslash paths) while preserving the invariant that stored root is absolute and normalized.
        var normalizedRoot = root;
        if (!root.isAbsolute()) {
            normalizedRoot = root.toAbsolutePath().normalize();
        } else {
            normalizedRoot = root.normalize();
        }

        // Validation
        if (!normalizedRoot.equals(normalizedRoot.normalize())) {
            throw new IllegalArgumentException("Root must be normalized, got " + normalizedRoot);
        }
        if (relPath.isAbsolute()) {
            throw new IllegalArgumentException("RelPath must be relative, got " + relPath);
        }

        this.root = normalizedRoot;
        this.relPath = relPath.normalize();
    }

    /**
     * Json factory used during deserialization. This method enforces that JSON-supplied root values are absolute,
     * ensuring legacy/deserialized data must explicitly present an absolute repo root. Programmatic callers may still
     * use the public constructor which will accept and normalize relative roots for convenience.
     */
    @JsonCreator
    public static ProjectFile forJson(@JsonProperty("root") Path root, @JsonProperty("relPath") Path relPath) {
        if (root == null) {
            throw new IllegalArgumentException("Root must not be null");
        }
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException("Root must be absolute, got " + root);
        }
        // Delegate to the constructor which will perform normalization/validation of relPath
        return new ProjectFile(root, relPath);
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

    /** Also relative (but unlike raw Path.getParent, ours returns empty path instead of null) */
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
        return Objects.equals(root, projectFile.root) && Objects.equals(relPath, projectFile.relPath);
    }

    @Override
    public int hashCode() {
        return relPath.hashCode();
    }
}
