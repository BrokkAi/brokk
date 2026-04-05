package ai.brokk.git;

import static ai.brokk.project.ICoreProject.BROKK_DIR;

import ai.brokk.analyzer.ProjectFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import javax.annotation.Nullable;
import org.eclipse.jgit.api.errors.GitAPIException;

/** Implements portions of the IGitRepo for a project directory that is not git-enabled. */
public class LocalFileRepo implements IGitRepo {
    private final Path root;

    @Nullable
    private Set<ProjectFile> trackedFilesCache;

    public LocalFileRepo(Path root) {
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("Root path must be an existing directory");
        }
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void add(Collection<ProjectFile> files) throws GitAPIException {
        // no-op
    }

    @Override
    public void add(ProjectFile file) throws GitAPIException {
        // no-op
    }

    @Override
    public void remove(ProjectFile file) throws GitAPIException {
        // no-op
    }

    @Override
    public synchronized void invalidateCaches() {
        trackedFilesCache = null;
    }

    @Override
    public synchronized Set<ProjectFile> getTrackedFiles() {
        if (trackedFilesCache != null) {
            return trackedFilesCache;
        }
        // LocalFileRepo walks all files, excluding Brokk metadata directory
        trackedFilesCache = FileSystemWalker.walk(root, Set.of(BROKK_DIR));
        return trackedFilesCache;
    }
}
