package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;

/** Implements portions of the IGitRepo for a project directory that is not git-enabled. */
public class LocalFileRepo implements IGitRepo {
    private static final Logger logger = LogManager.getLogger(LocalFileRepo.class);
    private final Path root;

    public LocalFileRepo(Path root) {
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("Root path must be an existing directory");
        }
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void add(List<ProjectFile> files) throws GitAPIException {
        // no-op
    }

    @Override
    public void add(Path path) throws GitAPIException {
        // no-op
    }

    @Override
    public void remove(ProjectFile file) throws GitAPIException {
        // no-op
    }

    @Override
    public Set<ProjectFile> getTrackedFiles() {
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
            return Set.of();
        }
        return trackedFiles;
    }
}
