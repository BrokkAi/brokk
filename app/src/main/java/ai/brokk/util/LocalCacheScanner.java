package ai.brokk.util;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.maven.artifact.versioning.ComparableVersion;

/**
 * Scans local dependency caches (Maven, Gradle, Ivy, Coursier, sbt) for JAR files.
 * Shared between GUI (JavaLanguage) and CLI (DependencyTools).
 */
public final class LocalCacheScanner {
    private static final Logger logger = LogManager.getLogger(LocalCacheScanner.class);

    private LocalCacheScanner() {}

    /**
     * Returns paths to common local cache roots.
     * Includes Maven local repo, Gradle caches, Ivy cache, Coursier, and sbt.
     */
    public static List<Path> getCacheRoots() {
        String userHome = System.getProperty("user.home");
        if (userHome == null) {
            logger.warn("Could not determine user home directory.");
            return List.of();
        }
        Path homePath = Path.of(userHome);

        List<Path> rootsToScan = new ArrayList<>();

        // Default locations that exist on all OSes
        rootsToScan.add(homePath.resolve(".m2").resolve("repository"));
        rootsToScan.add(homePath.resolve(".gradle")
                .resolve("caches")
                .resolve("modules-2")
                .resolve("files-2.1"));
        rootsToScan.add(homePath.resolve(".ivy2").resolve("cache"));
        rootsToScan.add(
                homePath.resolve(".cache").resolve("coursier").resolve("v1").resolve("https"));
        rootsToScan.add(homePath.resolve(".sbt"));

        // Honour user-supplied overrides
        Optional.ofNullable(System.getenv("MAVEN_REPO")).map(Path::of).ifPresent(rootsToScan::add);

        Optional.ofNullable(System.getProperty("maven.repo.local"))
                .map(Path::of)
                .ifPresent(rootsToScan::add);

        Optional.ofNullable(System.getenv("GRADLE_USER_HOME"))
                .map(Path::of)
                .map(p -> p.resolve("caches").resolve("modules-2").resolve("files-2.1"))
                .ifPresent(rootsToScan::add);

        // Windows-specific cache roots
        if (Environment.isWindows()) {
            Optional.ofNullable(System.getenv("LOCALAPPDATA")).ifPresent(localAppData -> {
                Path lad = Path.of(localAppData);
                rootsToScan.add(
                        lad.resolve("Coursier").resolve("cache").resolve("v1").resolve("https"));
                rootsToScan.add(lad.resolve("Gradle")
                        .resolve("caches")
                        .resolve("modules-2")
                        .resolve("files-2.1"));
            });
        }

        // macOS-specific cache roots
        if (Environment.isMacOs()) {
            rootsToScan.add(homePath.resolve("Library")
                    .resolve("Caches")
                    .resolve("Coursier")
                    .resolve("v1")
                    .resolve("https"));
        }

        return rootsToScan.stream().distinct().toList();
    }

    /**
     * Returns the Maven local repository artifact directory for the given groupId and artifactId.
     */
    private static Path getMavenArtifactDir(String groupId, String artifactId) {
        String userHome = System.getProperty("user.home");
        if (userHome == null) {
            return Path.of("");
        }
        Path m2Repo = Path.of(userHome, ".m2", "repository");
        return m2Repo.resolve(groupId.replace('.', '/')).resolve(artifactId);
    }

    /**
     * Constructs the Maven local repository path for the given coordinates.
     */
    private static Path getMavenLocalPath(String groupId, String artifactId, String version) {
        return getMavenArtifactDir(groupId, artifactId).resolve(version).resolve(artifactId + "-" + version + ".jar");
    }

    /**
     * Finds the latest version of an artifact available in local caches.
     * Checks Maven local repo structure first, then scans other caches.
     *
     * @param groupId Maven group ID
     * @param artifactId Maven artifact ID
     * @return The latest version string if any version is found locally
     */
    public static Optional<String> findLatestVersion(String groupId, String artifactId) {
        var versions = new ArrayList<String>();

        // Check Maven local repo structure (fast, predictable paths)
        var artifactDir = getMavenArtifactDir(groupId, artifactId);
        if (Files.isDirectory(artifactDir)) {
            try (Stream<Path> subdirs = Files.list(artifactDir)) {
                subdirs.filter(Files::isDirectory).forEach(versionDir -> {
                    var version = versionDir.getFileName().toString();
                    var jarPath = versionDir.resolve(artifactId + "-" + version + ".jar");
                    if (Files.exists(jarPath)) {
                        versions.add(version);
                    }
                });
            } catch (IOException e) {
                logger.warn("Error listing versions in {}: {}", artifactDir, e.getMessage());
            }
        }

        // Scan other caches by filename pattern
        var jarPattern = Pattern.compile(Pattern.quote(artifactId) + "-(.+)\\.jar$");
        for (var root : getCacheRoots()) {
            if (!Files.isDirectory(root) || root.toString().contains(".m2")) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    var name = p.getFileName().toString();
                    if (name.endsWith("-sources.jar") || name.endsWith("-javadoc.jar")) {
                        return;
                    }
                    var matcher = jarPattern.matcher(name);
                    if (matcher.matches()) {
                        versions.add(matcher.group(1));
                    }
                });
            } catch (IOException | SecurityException e) {
                logger.debug("Error scanning {}: {}", root, e.getMessage());
            }
        }

        if (versions.isEmpty()) {
            logger.debug("No local versions found for {}:{}", groupId, artifactId);
            return Optional.empty();
        }

        // Sort using Maven's version comparison and return the highest
        var latest = versions.stream()
                .max(Comparator.comparing(ComparableVersion::new))
                .orElseThrow();
        logger.info("Found latest local version for {}:{} -> {}", groupId, artifactId, latest);
        return Optional.of(latest);
    }

    /**
     * Finds a specific artifact by coordinates in local caches.
     * Checks Maven local repo structure first (O(1) lookup) for all known Maven roots,
     * then scans other caches by filename.
     *
     * @param groupId Maven group ID
     * @param artifactId Maven artifact ID
     * @param version Version string
     * @return Path to the JAR if found in local cache
     */
    public static Optional<Path> findArtifact(String groupId, String artifactId, String version) {
        var roots = getCacheRoots();

        // 1. Fast path: check for Maven-style directory structure in all likely roots
        for (var root : roots) {
            if (root.toString().contains(".m2") || root.toString().contains("maven")) {
                var found = findInMavenRepoStructure(root, groupId, artifactId, version);
                if (found.isPresent()) {
                    return found;
                }
            }
        }

        // 2. Slow path: scan other caches (Gradle, Ivy, etc.) by filename match
        var expectedName = artifactId + "-" + version + ".jar";
        for (var root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            // Skip roots that look like Maven repos as we already checked them
            if (root.toString().contains(".m2") || root.toString().contains("maven")) {
                continue;
            }

            logger.debug("Scanning {} for {}", root, expectedName);
            try (Stream<Path> walk = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) {
                var found = walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equals(expectedName))
                        .findFirst();
                if (found.isPresent()) {
                    logger.info("Found {} in cache: {}", expectedName, found.get());
                    return found;
                }
            } catch (IOException e) {
                logger.warn("Error scanning {}: {}", root, e.getMessage());
            } catch (SecurityException e) {
                logger.warn("Permission denied scanning {}: {}", root, e.getMessage());
            }
        }

        logger.debug("Artifact {}:{}:{} not found in local caches", groupId, artifactId, version);
        return Optional.empty();
    }

    private static Optional<Path> findInMavenRepoStructure(
            Path root, String groupId, String artifactId, String version) {
        if (!Files.isDirectory(root)) return Optional.empty();
        Path p = root.resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version)
                .resolve(artifactId + "-" + version + ".jar");

        if (Files.exists(p) && Files.isRegularFile(p)) {
            logger.info("Found artifact in Maven-style cache: {}", p);
            return Optional.of(p);
        }
        return Optional.empty();
    }

    /**
     * Lists all JAR files in local caches.
     * Used by JavaLanguage.getDependencyCandidates() for the GUI picker.
     * Excludes sources and javadoc JARs.
     *
     * @return List of paths to JAR files found in local caches
     */
    public static List<Path> listAllJars() {
        long startTime = System.currentTimeMillis();

        var uniqueRoots = getCacheRoots();
        var jarFiles = uniqueRoots.parallelStream()
                .filter(Files::isDirectory)
                .peek(root -> logger.debug("Scanning for JARs under: {}", root))
                .flatMap(root -> {
                    try (Stream<Path> s = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) {
                        return s.filter(Files::isRegularFile).toList().stream();
                    } catch (IOException e) {
                        logger.warn("Error walking directory {}: {}", root, e.getMessage());
                        return Stream.empty();
                    } catch (SecurityException e) {
                        logger.warn("Permission denied accessing directory {}: {}", root, e.getMessage());
                        return Stream.empty();
                    }
                })
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    return name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar");
                })
                .toList();

        long duration = System.currentTimeMillis() - startTime;
        logger.info("Found {} JAR files in common dependency locations in {} ms", jarFiles.size(), duration);

        return jarFiles;
    }
}
