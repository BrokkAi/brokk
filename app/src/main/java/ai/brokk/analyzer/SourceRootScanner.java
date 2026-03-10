package ai.brokk.analyzer;

import ai.brokk.project.IProject;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jspecify.annotations.NullMarked;

/**
 * Scans a project for common source root patterns based on language extensions.
 */
@NullMarked
public final class SourceRootScanner {
    private static final Logger logger = LogManager.getLogger(SourceRootScanner.class);

    private SourceRootScanner() {}

    /**
     * Scans the project root and subdirectories for source roots for a specific language.
     * Respects .gitignore rules and skips hidden directories.
     */
    @Blocking
    public static List<String> scan(IProject project, Language language) {
        Path root = project.getRoot();
        List<String> foundRoots = new ArrayList<>();

        Set<String> patterns = new HashSet<>();
        for (String ext : language.getExtensions()) {
            patterns.add("src/main/" + ext);
            patterns.add("src/test/" + ext);
        }

        // Special Case: Java projects often have Kotlin sources
        if (language == Languages.JAVA) {
            patterns.add("src/main/kotlin");
            patterns.add("src/test/kotlin");
        }

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path relPath = root.relativize(dir);
                    String relPathStr = relPath.toString().replace('\\', '/');

                    // Skip hidden directories (except the root itself which is empty string)
                    if (!relPathStr.isEmpty() && dir.getFileName().toString().startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    // Respect gitignore (don't ignore the root itself)
                    if (!relPathStr.isEmpty() && project.isGitignored(relPath)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    // Check if this directory ends with a known source root pattern
                    for (String pattern : patterns) {
                        if (relPathStr.equals(pattern) || relPathStr.endsWith("/" + pattern)) {
                            foundRoots.add(relPathStr);
                            // We found a leaf source root, no need to go deeper for more src/main/ext
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.error("Error scanning for {} source roots in {}: {}", language.name(), root, e.getMessage());
        }

        // Fallback Logic: If no standard structures found, check if the project root contains any files
        // matching the language extensions. Limit depth to avoid scanning massive dependency folders.
        if (foundRoots.isEmpty()) {
            try (var stream = Files.walk(root, 5)) {
                Set<String> extensions = language.getExtensions();
                boolean hasMatchingFiles = stream.filter(Files::isRegularFile).anyMatch(p -> {
                    String fileName = p.toString();
                    return extensions.stream().anyMatch(ext -> fileName.endsWith("." + ext));
                });
                if (hasMatchingFiles) {
                    foundRoots.add(".");
                }
            } catch (IOException e) {
                logger.debug("Error checking for fallback {} files in {}: {}", language.name(), root, e.getMessage());
            }
        }

        return foundRoots.stream().distinct().sorted().toList();
    }
}
