package ai.brokk.git;

import ai.brokk.ExceptionReporter;
import ai.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility for walking filesystem trees and collecting files as ProjectFile instances.
 * Provides common filesystem traversal logic used by both LocalFileRepo and GitRepo fallback.
 */
public class FileSystemWalker {
    private static final Logger logger = LogManager.getLogger(FileSystemWalker.class);

    /**
     * Walks the filesystem starting from root and collects all regular files as ProjectFile instances.
     *
     * @param root Directory to walk (becomes the ProjectFile root)
     * @param skipDirs Directory names to skip (e.g., ".git", ".brokk")
     * @return Set of discovered ProjectFile instances
     */
    @org.jetbrains.annotations.Blocking
    public static Set<ProjectFile> walk(Path root, Set<String> skipDirs) {
        var files = new HashSet<ProjectFile>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // Skip directories in the exclusion list
                    if (dir.getFileName() != null
                            && skipDirs.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    // Skip unreadable directories
                    if (!Files.isReadable(dir)) {
                        logger.warn("Skipping inaccessible directory: {}", dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    // Use attrs.isRegularFile() - more efficient and consistent
                    // BasicFileAttributes already resolved by walkFileTree (follows symlinks by default)
                    if (attrs.isRegularFile()) {
                        var relPath = root.relativize(file);
                        files.add(new ProjectFile(root, relPath));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // Log and skip - don't make additional I/O calls in error handler
                    logger.warn("Failed to access path: {}", file, exc);
                    // Return SKIP_SUBTREE for safety (works for both files and directories)
                    return FileVisitResult.SKIP_SUBTREE;
                }
            });
        } catch (IOException e) {
            logger.error("Unexpected error walking directory tree starting at {}", root, e);
            ExceptionReporter.tryReportException(e);
            return Set.of();
        }
        return files;
    }
}
