package ai.brokk.analyzer;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.pcollections.HashTreePMap;
import org.pcollections.PMap;

/**
 * Encapsulates the import relationships within a project.
 *
 * <p>The forward map (imports) tracks which CodeUnits are imported by a given ProjectFile.
 * The reverse map (reverseImports) tracks which ProjectFiles import a given ProjectFile.
 */
@NullMarked
public record ImportGraph(
        PMap<ProjectFile, Set<CodeUnit>> imports, PMap<ProjectFile, Set<ProjectFile>> reverseImports) {

    public static ImportGraph empty() {
        return new ImportGraph(HashTreePMap.empty(), HashTreePMap.empty());
    }

    public static ImportGraph from(
            Map<ProjectFile, Set<CodeUnit>> imports, Map<ProjectFile, Set<ProjectFile>> reverseImports) {
        return new ImportGraph(HashTreePMap.from(imports), HashTreePMap.from(reverseImports));
    }

    /**
     * Returns the set of CodeUnits imported by the given file.
     */
    public Set<CodeUnit> importedCodeUnitsOf(ProjectFile file) {
        return imports.getOrDefault(file, Set.of());
    }

    /**
     * Returns the set of files that import the given file.
     */
    public Set<ProjectFile> referencingFilesOf(ProjectFile file) {
        return reverseImports.getOrDefault(file, Set.of());
    }
}
