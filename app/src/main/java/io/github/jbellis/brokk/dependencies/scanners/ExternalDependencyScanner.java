package io.github.jbellis.brokk.dependencies.scanners;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.dependencies.IExternalDependency;
import java.util.List;

/** Interface for scanning external dependency sources (Maven repos, npm caches, etc.). */
public interface ExternalDependencyScanner<T extends IExternalDependency> {

    /** Returns true if this scanner supports the given language */
    boolean supports(Language language);

    /** Scan for dependency candidates for the given project */
    List<T> scan(IProject project);

    /** Human-readable name of the source system (e.g., "Maven", "Gradle") */
    String sourceSystem();
}
