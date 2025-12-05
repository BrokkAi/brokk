package ai.brokk.git;

import ai.brokk.ExceptionReporter;
import ai.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;

/** Implements portions of the IGitRepo for a project directory that is not git-enabled. */
public class LocalFileRepo implements IGitRepo {
    private static final Logger logger = LogManager.getLogger(LocalFileRepo.class);
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
        var trackedFiles = new HashSet<ProjectFile>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // Skip directories we can't read to avoid AccessDeniedException later
                    if (!Files.isReadable(dir)) {
                        logger.warn("Skipping inaccessible directory: {}", dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (Files.isRegularFile(file)) {
                        var relPath = root.relativize(file);
                        trackedFiles.add(new ProjectFile(root, relPath));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    if (exc instanceof AccessDeniedException) {
                        logger.warn("Skipping inaccessible file/directory: {}", file, exc);
                        if (Files.isDirectory(file)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    logger.error("Error visiting file: {}", file, exc);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.error("Unexpected error walking directory tree starting at {}", root, e);
            ExceptionReporter.tryReportException(e);
            return Set.of();
        }
        trackedFilesCache = trackedFiles;
        return trackedFilesCache;
    }
}
