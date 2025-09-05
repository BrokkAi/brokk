package io.github.jbellis.brokk.dependencies;

import io.github.jbellis.brokk.analyzer.Language;
import java.nio.file.Path;

/**
 * Represents an external dependency candidate discovered on the system but not yet imported. This is a sealed interface
 * with language-specific implementations.
 */
public sealed interface IExternalDependency permits JavaDependency {

    enum Kind {
        BYTECODE,
        DIRECTORY
    }

    enum ImportStrategy {
        DECOMPILE,
        COPY_DIRECTORY
    }

    /** Path to the candidate artifact (JAR file or directory) */
    Path sourcePath();

    /** Language this dependency is associated with */
    Language language();

    /** Display name for UI (e.g., "org.slf4j:slf4j-api:2.0.13" or directory name) */
    String displayName();

    /** Grouping key for UI nesting (e.g., "org.slf4j:slf4j-api" for multiple versions) */
    String groupingKey();

    /** How this candidate should be imported */
    ImportStrategy importStrategy();

    /** Kind of dependency (JAR or directory) */
    Kind kind();

    /** Source system that discovered this (e.g., "Maven", "Gradle", "NPM") */
    String sourceSystem();

    /** Generate a sanitized directory name for importing under .brokk/dependencies */
    String sanitizedImportName();
}
