package io.github.jbellis.brokk.dependencies.scanners;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.dependencies.JavaDependency;
import io.github.jbellis.brokk.util.Environment;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GradleCacheScanner implements ExternalDependencyScanner<JavaDependency> {
    private static final Logger logger = LogManager.getLogger(GradleCacheScanner.class);

    @Override
    public boolean supports(Language language) {
        return language == Language.JAVA;
    }

    @Override
    public String sourceSystem() {
        return "Gradle";
    }

    @Override
    public List<JavaDependency> scan(IProject project) {
        var candidates = new ArrayList<JavaDependency>();

        String userHome = System.getProperty("user.home");
        if (userHome == null) {
            logger.warn("Could not determine user home directory for Gradle cache scan.");
            return candidates;
        }

        Path gradleCache = Path.of(userHome)
                .resolve(".gradle")
                .resolve("caches")
                .resolve("modules-2")
                .resolve("files-2.1");

        // Check for custom Gradle user home
        Optional.ofNullable(System.getenv("GRADLE_USER_HOME"))
                .map(Path::of)
                .map(p -> p.resolve("caches").resolve("modules-2").resolve("files-2.1"))
                .ifPresent(customCache -> scanGradleCache(customCache, candidates));

        // Windows-specific location
        if (Environment.isWindows()) {
            Optional.ofNullable(System.getenv("LOCALAPPDATA")).ifPresent(localAppData -> {
                Path winCache = Path.of(localAppData)
                        .resolve("Gradle")
                        .resolve("caches")
                        .resolve("modules-2")
                        .resolve("files-2.1");
                scanGradleCache(winCache, candidates);
            });
        }

        // Default location
        scanGradleCache(gradleCache, candidates);

        logger.debug("Found {} Gradle dependency candidates", candidates.size());
        return candidates;
    }

    private void scanGradleCache(Path cacheRoot, List<JavaDependency> candidates) {
        if (!Files.exists(cacheRoot) || !Files.isDirectory(cacheRoot)) {
            logger.debug("Gradle cache does not exist: {}", cacheRoot);
            return;
        }

        logger.debug("Scanning Gradle cache: {}", cacheRoot);

        try (Stream<Path> pathStream = Files.walk(cacheRoot, FileVisitOption.FOLLOW_LINKS)) {
            pathStream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".jar")
                                && !name.endsWith("-sources.jar")
                                && !name.endsWith("-javadoc.jar");
                    })
                    .forEach(jarPath ->
                            parseJarFromGradlePath(jarPath, cacheRoot).ifPresent(candidates::add));
        } catch (IOException | SecurityException e) {
            logger.warn("Error scanning Gradle cache {}: {}", cacheRoot, e.getMessage());
        }
    }

    private Optional<JavaDependency> parseJarFromGradlePath(Path jarPath, Path cacheRoot) {
        try {
            // Gradle cache structure: groupId/artifactId/version/hash/artifactId-version[-classifier].jar
            Path relativePath = cacheRoot.relativize(jarPath);
            List<String> pathParts = new ArrayList<>();
            for (Path part : relativePath) {
                pathParts.add(part.toString());
            }

            if (pathParts.size() < 5) {
                return Optional.empty();
            }

            String fileName = pathParts.get(pathParts.size() - 1);
            // Skip hash directory
            String version = pathParts.get(pathParts.size() - 3);
            String artifactId = pathParts.get(pathParts.size() - 4);
            String groupId = pathParts.get(pathParts.size() - 5);

            // Extract classifier if present
            String classifier = null;
            String expectedPrefix = artifactId + "-" + version;
            if (fileName.startsWith(expectedPrefix)) {
                String suffix = fileName.substring(expectedPrefix.length());
                if (suffix.startsWith("-") && suffix.endsWith(".jar")) {
                    classifier = suffix.substring(1, suffix.length() - 4);
                }
            }

            return Optional.of(
                    new JavaDependency(jarPath, groupId, artifactId, version, "jar", classifier, sourceSystem()));

        } catch (Exception e) {
            logger.debug("Failed to parse Gradle JAR {}: {}", jarPath, e.getMessage());
            return Optional.empty();
        }
    }
}
