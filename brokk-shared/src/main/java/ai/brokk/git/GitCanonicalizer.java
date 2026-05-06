package ai.brokk.git;

import ai.brokk.analyzer.ProjectFile;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/** Canonicalizes project files from a historical commit window to their current path. */
public final class GitCanonicalizer {
    private final Map<String, Integer> indexByCommit;
    private final NavigableMap<Integer, List<RenameEdge>> renamesByIndex;

    GitCanonicalizer(Map<String, Integer> indexByCommit, NavigableMap<Integer, List<RenameEdge>> renamesByIndex) {
        this.indexByCommit = indexByCommit;
        this.renamesByIndex = renamesByIndex;
    }

    /**
     * Returns the canonical (current-as-of-HEAD) ProjectFile for {@code pathAtCommit},
     * by applying any renames that occur after {@code commitId} within the window.
     */
    public ProjectFile canonicalize(String commitId, ProjectFile pathAtCommit) {
        Integer startIdx = indexByCommit.get(commitId);
        if (startIdx == null) {
            return pathAtCommit;
        }

        var current = pathAtCommit;
        for (var entry : renamesByIndex.headMap(startIdx, false).descendingMap().entrySet()) {
            for (var edge : entry.getValue()) {
                if (edge.old().equals(current)) {
                    current = edge.newPath();
                }
            }
        }
        return current;
    }

    /** A single rename observed in a commit's diff (old -> new). */
    public record RenameEdge(ProjectFile old, ProjectFile newPath) {}
}
