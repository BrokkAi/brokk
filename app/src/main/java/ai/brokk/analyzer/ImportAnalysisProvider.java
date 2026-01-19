package ai.brokk.analyzer;

import java.util.Set;

/**
 * Capability provider for analyzers that support resolved import tracking.
 *
 * <p>Unlike {@link IAnalyzer#importStatementsOf(ProjectFile)} which returns raw text snippets,
 * this provider allows deep analysis of the project's dependency graph by resolving imports
 * to actual {@link CodeUnit} definitions and tracking which files reference each other.
 *
 * <p>Implementation of this interface indicates the analyzer can perform semantic resolution
 * of symbols across file boundaries.
 */
public interface ImportAnalysisProvider extends CapabilityProvider {

    /**
     * Retrieves the resolved {@link CodeUnit}s that are imported by the given file.
     *
     * <p>This includes types, functions, or modules explicitly or implicitly (e.g., via wildcards)
     * brought into the file's scope.
     *
     * @param file the project file to analyze
     * @return an unmodifiable set of resolved CodeUnits
     */
    Set<CodeUnit> importedCodeUnitsOf(ProjectFile file);

    /**
     * Returns the set of files that import or reference the given file.
     *
     * <p>This is the inverse of {@link #importedCodeUnitsOf(ProjectFile)} and is typically used
     * for impact analysis or ranking file relevance.
     *
     * @param file the project file being referenced
     * @return a set of files that depend on the given file
     */
    Set<ProjectFile> referencingFilesOf(ProjectFile file);
}
