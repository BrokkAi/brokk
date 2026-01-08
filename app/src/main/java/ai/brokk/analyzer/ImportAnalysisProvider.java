package ai.brokk.analyzer;

import java.util.Set;
import org.jspecify.annotations.NullMarked;

/**
 * Capability provider for analyzing import relationships between files and code units.
 */
@NullMarked
public interface ImportAnalysisProvider extends CapabilityProvider {

    /**
     * Returns the set of {@link CodeUnit}s imported by the given file.
     */
    Set<CodeUnit> importedCodeUnitsOf(ProjectFile file);

    /**
     * Returns the set of files that import the given file.
     */
    Set<ProjectFile> referencingFilesOf(ProjectFile file);
}
