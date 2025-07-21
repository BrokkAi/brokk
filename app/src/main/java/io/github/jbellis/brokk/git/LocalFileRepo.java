package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;

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
    public Set<ProjectFile> getTrackedFiles() {
        Set<ProjectFile> trackedFiles = new HashSet<>();
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
                        // If it's a directory causing the issue, skip its subtree
                        if (Files.isDirectory(file)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        // If it's a file, just skip this file
                        return FileVisitResult.CONTINUE;
                    }
                    // For other IOExceptions, log the error and decide whether to continue or terminate
                    // For now, we log and continue, but termination might be safer depending on the error type.
                    logger.error("Error visiting file: {}", file, exc);
                    return FileVisitResult.CONTINUE; // or return FileVisitResult.TERMINATE;
                }
            });
        } catch (IOException e) {
            // shouldn't happen -- our visitor methods handle IOExceptions internally
            logger.error("Unexpected error walking directory tree starting at {}", root, e);
            return Set.of();
        }
        return trackedFiles;
    }
}
