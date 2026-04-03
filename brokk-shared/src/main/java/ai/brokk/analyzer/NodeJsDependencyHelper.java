package ai.brokk.analyzer;

import ai.brokk.project.ICoreProject;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Shared dependency management logic for Node.js-based languages (JavaScript, TypeScript). Both languages use the same
 * npm/node_modules ecosystem for dependency management.
 */
public final class NodeJsDependencyHelper {
    private static final Logger logger = LogManager.getLogger(NodeJsDependencyHelper.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private NodeJsDependencyHelper() {
        // Utility class - prevent instantiation
    }

    /**
     * Scans for Node.js dependency candidates in a project's node_modules directory. This method finds all package
     * directories under node_modules, including scoped packages, but excludes utility directories like .bin.
     *
     * @param project The project to scan for dependencies
     * @return List of paths to dependency candidate directories
     */
    public static List<Path> getDependencyCandidates(ICoreProject project) {
        logger.debug("Scanning for Node.js dependency candidates in project: {}", project.getRoot());
        var results = new ArrayList<Path>();
        Path nodeModules = project.getRoot().resolve("node_modules");

        if (Files.isDirectory(nodeModules)) {
            logger.debug("Scanning node_modules directory: {}", nodeModules);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(nodeModules)) {
                for (Path entry : ds) {
                    String name = entry.getFileName().toString();
                    if (name.equals(".bin")) continue; // skip executables

                    if (name.startsWith("@")) { // scoped packages
                        logger.debug("Found scoped package directory: {}", entry);
                        try (DirectoryStream<Path> scoped = Files.newDirectoryStream(entry)) {
                            for (Path scopedPkg : scoped) {
                                if (Files.isDirectory(scopedPkg)) {
                                    logger.debug("Found scoped dependency candidate: {}", scopedPkg);
                                    results.add(scopedPkg);
                                }
                            }
                        } catch (IOException e) {
                            logger.warn("Error scanning scoped package directory {}: {}", entry, e.getMessage());
                        }
                    } else if (Files.isDirectory(entry)) {
                        logger.debug("Found dependency candidate: {}", entry);
                        results.add(entry);
                    }
                }
            } catch (IOException e) {
                logger.warn("Error scanning node_modules directory {}: {}", nodeModules, e.getMessage());
            }
        } else {
            logger.debug("node_modules directory not found at: {}", nodeModules);
        }

        logger.debug("Found {} Node.js dependency candidates.", results.size());
        return results;
    }

    /** Build a list of dependency packages with display name, kind, and file counts for ILP. */
    public static List<Language.DependencyCandidate> listDependencyPackages(ICoreProject project) {
        var candidates = getDependencyCandidates(project);
        var pkgs = new ArrayList<Language.DependencyCandidate>();

        var rootPkg = readPackageJson(project.getRoot().resolve("package.json"));
        var deps = rootPkg == null ? Set.<String>of() : rootPkg.dependencies.keySet();
        var devDeps = rootPkg == null ? Set.<String>of() : rootPkg.devDependencies.keySet();
        var peerDeps = rootPkg == null ? Set.<String>of() : rootPkg.peerDependencies.keySet();

        for (var dir : candidates) {
            var meta = readPackageJson(dir.resolve("package.json"));
            if (meta == null) continue;

            var display = meta.name.isEmpty()
                    ? dir.getFileName().toString()
                    : (meta.version.isEmpty() ? meta.name : meta.name + "@" + meta.version);

            long files = countNodeFiles(dir);
            var kind = pickKind(meta.name, deps, devDeps, peerDeps);

            pkgs.add(new Language.DependencyCandidate(display, dir, kind, files));
        }

        pkgs.sort(Comparator.comparing(Language.DependencyCandidate::displayName));
        return pkgs;
    }

    private static Language.DependencyKind pickKind(
            String name, Set<String> deps, Set<String> devDeps, Set<String> peerDeps) {
        if (deps.contains(name)) return Language.DependencyKind.NORMAL;
        if (peerDeps.contains(name)) return Language.DependencyKind.BUILD; // treat peer as build-time
        if (devDeps.contains(name)) return Language.DependencyKind.DEV;
        return Language.DependencyKind.TRANSITIVE;
    }

    private static long countNodeFiles(Path root) {
        try (var stream = Files.walk(root, Integer.MAX_VALUE, FileVisitOption.FOLLOW_LINKS)) {
            // do not skip build/dist, that's where you find usually the lib code
            var skipDirs = Set.of("node_modules", ".pnpm", ".git", "coverage", "test", "tests", ".nyc_output");
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        var rel =
                                root.relativize(p).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                        for (var d : skipDirs) {
                            if (rel.startsWith(d + "/")) return false;
                        }
                        var name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (name.equals("package.json")) return true;
                        if (name.startsWith("readme") || name.startsWith("license") || name.startsWith("copying"))
                            return true;
                        return name.endsWith(".js")
                                || name.endsWith(".mjs")
                                || name.endsWith(".cjs")
                                || name.endsWith(".jsx")
                                || name.endsWith(".ts")
                                || name.endsWith(".tsx")
                                || name.endsWith(".d.ts");
                    })
                    .count();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Checks if the given path is likely already analyzed as part of the project's primary sources for Node.js-based
     * projects. This excludes node_modules dependencies since they are external.
     *
     * @param project The current project
     * @param pathToImport The absolute path to check
     * @return true if the path is considered part of the project's analyzed sources, false otherwise
     */
    public static boolean isAnalyzed(ICoreProject project, Path pathToImport) {
        assert pathToImport.isAbsolute() : "Path must be absolute for isAnalyzed check: " + pathToImport;
        Path projectRoot = project.getRoot();
        Path normalizedPathToImport = pathToImport.normalize();

        if (!normalizedPathToImport.startsWith(projectRoot)) {
            return false; // Not part of this project
        }

        // Check if the path is node_modules or inside node_modules directly under project root
        Path nodeModulesPath = projectRoot.resolve("node_modules");
        return !normalizedPathToImport.startsWith(nodeModulesPath);
    }

    public static boolean supportsDependencyKinds() {
        return true;
    }

    // ---- helpers ----

    /**
     * Reads a package.json located inside the given directory.
     *
     * @param packageDir directory that may contain a package.json
     * @return NodePackage metadata if present and parsable; null otherwise
     */
    public static @Nullable NodePackage readPackageJsonFromDir(Path packageDir) {
        return readPackageJson(packageDir.resolve("package.json"));
    }

    /**
     * Builds a human-friendly display name ("name version") from NodePackage metadata. If version is empty, returns
     * just the name. Returns empty string if name is empty.
     */
    public static String displayNameFrom(NodePackage pkg) {
        var name = pkg.name;
        var version = pkg.version;
        if (name.isEmpty()) return "";
        return version.isEmpty() ? name : name + "@" + version;
    }

    private static @Nullable NodePackage readPackageJson(Path pkgJsonPath) {
        try {
            if (!Files.isRegularFile(pkgJsonPath)) return null;
            return MAPPER.readValue(Files.readString(pkgJsonPath), NodePackage.class);
        } catch (IOException e) {
            logger.debug("Failed to read package.json at {}: {}", pkgJsonPath, e.toString());
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NodePackage {
        public String name = "";
        public String version = "";
        public Map<String, String> dependencies = Map.of();
        public Map<String, String> devDependencies = Map.of();
        public Map<String, String> peerDependencies = Map.of();
        public Map<String, String> optionalDependencies = Map.of();
    }
}
