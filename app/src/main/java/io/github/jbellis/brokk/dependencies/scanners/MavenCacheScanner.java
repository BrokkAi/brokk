package io.github.jbellis.brokk.dependencies.scanners;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.dependencies.JavaDependency;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MavenCacheScanner implements ExternalDependencyScanner<JavaDependency> {
    private static final Logger logger = LogManager.getLogger(MavenCacheScanner.class);

    @Override
    public boolean supports(Language language) {
        return language == Language.JAVA;
    }

    @Override
    public String sourceSystem() {
        return "Maven";
    }

    @Override
    public List<JavaDependency> scan(IProject project) {
        var candidates = new ArrayList<JavaDependency>();

        String userHome = System.getProperty("user.home");
        if (userHome == null) {
            logger.warn("Could not determine user home directory for Maven cache scan.");
            return candidates;
        }

        Path m2Repository = Path.of(userHome).resolve(".m2").resolve("repository");

        // Check for custom Maven repository locations
        Optional.ofNullable(System.getenv("MAVEN_REPO"))
                .map(Path::of)
                .ifPresent(customRepo -> scanMavenRepository(customRepo, candidates));

        Optional.ofNullable(System.getProperty("maven.repo.local"))
                .map(Path::of)
                .ifPresent(customRepo -> scanMavenRepository(customRepo, candidates));

        // Scan default location
        scanMavenRepository(m2Repository, candidates);

        logger.debug("Found {} Maven dependency candidates", candidates.size());
        return candidates;
    }

    private void scanMavenRepository(Path repository, List<JavaDependency> candidates) {
        if (!Files.exists(repository) || !Files.isDirectory(repository)) {
            logger.debug("Maven repository does not exist: {}", repository);
            return;
        }

        logger.debug("Scanning Maven repository: {}", repository);

        try (Stream<Path> pathStream = Files.walk(repository, FileVisitOption.FOLLOW_LINKS)) {
            pathStream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".jar")
                                && !name.endsWith("-sources.jar")
                                && !name.endsWith("-javadoc.jar");
                    })
                    .forEach(jarPath ->
                            parseJarFromMavenPath(jarPath, repository).ifPresent(candidates::add));
        } catch (IOException | SecurityException e) {
            logger.warn("Error scanning Maven repository {}: {}", repository, e.getMessage());
        }
    }

    private Optional<JavaDependency> parseJarFromMavenPath(Path jarPath, Path repositoryRoot) {
        try {
            // First try to read GAV from pom.properties inside the JAR
            var gavFromPom = readGavFromPomProperties(jarPath);
            if (gavFromPom.isPresent()) {
                return gavFromPom.map(gav -> new JavaDependency(
                        jarPath, gav.groupId, gav.artifactId, gav.version, "jar", null, sourceSystem()));
            }

            // Fallback to parsing Maven repository path structure
            Path relativePath = repositoryRoot.relativize(jarPath);
            return parseMavenPathStructure(jarPath, relativePath);

        } catch (Exception e) {
            logger.debug("Failed to parse Maven JAR {}: {}", jarPath, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<JavaDependency> parseMavenPathStructure(Path jarPath, Path relativePath) {
        // Maven repo structure: groupId/path/.../artifactId/version/artifactId-version[-classifier].jar
        List<String> pathParts = new ArrayList<>();
        for (Path part : relativePath) {
            pathParts.add(part.toString());
        }

        if (pathParts.size() < 4) {
            return Optional.empty();
        }

        String fileName = pathParts.get(pathParts.size() - 1);
        String version = pathParts.get(pathParts.size() - 2);
        String artifactId = pathParts.get(pathParts.size() - 3);

        // Build groupId from remaining path parts
        String groupId = String.join(".", pathParts.subList(0, pathParts.size() - 3));

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
    }

    private Optional<GAV> readGavFromPomProperties(Path jarPath) {
        try (var jarFs = FileSystems.newFileSystem(jarPath)) {
            var mavenDir = jarFs.getPath("META-INF", "maven");
            if (!Files.exists(mavenDir)) {
                return Optional.empty();
            }

            try (Stream<Path> paths = Files.walk(mavenDir)) {
                return paths.filter(p -> p.getFileName().toString().equals("pom.properties"))
                        .findFirst()
                        .flatMap(this::readPomProperties);
            }
        } catch (IOException | SecurityException e) {
            logger.debug("Could not read pom.properties from {}: {}", jarPath, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<GAV> readPomProperties(Path pomPropsPath) {
        try {
            var props = new Properties();
            try (var reader = Files.newBufferedReader(pomPropsPath)) {
                props.load(reader);
            }

            String groupId = props.getProperty("groupId");
            String artifactId = props.getProperty("artifactId");
            String version = props.getProperty("version");

            if (groupId != null && artifactId != null && version != null) {
                return Optional.of(new GAV(groupId, artifactId, version));
            }
        } catch (IOException e) {
            logger.debug("Could not read pom.properties {}: {}", pomPropsPath, e.getMessage());
        }
        return Optional.empty();
    }

    private record GAV(String groupId, String artifactId, String version) {}
}
