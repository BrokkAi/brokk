package io.github.jbellis.brokk.dependencies;

import io.github.jbellis.brokk.analyzer.Language;
import java.nio.file.Path;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/** Represents a Java dependency candidate (JAR file) with Maven GAV coordinates. */
public final record JavaDependency(
        Path sourcePath,
        String groupId,
        String artifactId,
        String version,
        @Nullable String packaging,
        @Nullable String classifier,
        String sourceSystem)
        implements IExternalDependency {

    public JavaDependency {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(sourceSystem, "sourceSystem");
    }

    @Override
    public Language language() {
        return Language.JAVA;
    }

    @Override
    public Kind kind() {
        return Kind.BYTECODE;
    }

    @Override
    public ImportStrategy importStrategy() {
        return ImportStrategy.DECOMPILE;
    }

    @Override
    public String displayName() {
        var base = "%s:%s:%s".formatted(groupId, artifactId, version);
        if (classifier != null && !classifier.isEmpty()) {
            base += ":" + classifier;
        }
        return base;
    }

    @Override
    public String groupingKey() {
        return "%s:%s".formatted(groupId, artifactId);
    }

    @Override
    public String sanitizedImportName() {
        // Use GAV-based naming for Java dependencies
        var name = "%s_%s_%s"
                .formatted(
                        groupId.replace('.', '_'),
                        artifactId.replace('-', '_'),
                        version.replace('.', '_').replace('-', '_'));
        if (classifier != null && !classifier.isEmpty()) {
            name += "_" + classifier.replace('-', '_');
        }
        return name;
    }
}
