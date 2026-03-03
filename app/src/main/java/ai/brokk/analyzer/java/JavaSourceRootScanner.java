package ai.brokk.analyzer.java;

import ai.brokk.project.IProject;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jspecify.annotations.NullMarked;

/**
 * Scans a project for common Java and Kotlin source root patterns.
 */
@NullMarked
public final class JavaSourceRootScanner {
    private static final Logger logger = LogManager.getLogger(JavaSourceRootScanner.class);

    private static final Set<String> SOURCE_ROOT_PATTERNS =
            Set.of("src/main/java", "src/test/java", "src/main/kotlin", "src/test/kotlin");

    private JavaSourceRootScanner() {}

    /**
     * Scans the project root and subdirectories for source roots.
     * Respects .gitignore rules and skips hidden directories.
     */
    @Blocking
    public static List<String> scan(IProject project) {
        Path root = project.getRoot();
        List<String> foundRoots = new ArrayList<>();

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
                    for (String pattern : SOURCE_ROOT_PATTERNS) {
                        if (relPathStr.equals(pattern) || relPathStr.endsWith("/" + pattern)) {
                            foundRoots.add(relPathStr);
                            // We found a leaf source root, no need to go deeper for more src/main/java
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.error("Error scanning for Java source roots in {}: {}", root, e.getMessage());
        }

        // Refinement: If no standard structures found, check if the project contains any .java files
        if (foundRoots.isEmpty()) {
            try (var stream = Files.walk(root)) {
                boolean hasJavaFiles = stream.filter(Files::isRegularFile)
                        .anyMatch(p -> p.toString().endsWith(".java"));
                if (hasJavaFiles) {
                    foundRoots.add(".");
                }
            } catch (IOException ignored) {
            }
        }

        return foundRoots.stream().distinct().sorted().toList();
    }
}
