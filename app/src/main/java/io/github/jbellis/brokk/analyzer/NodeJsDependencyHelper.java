package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared dependency management logic for Node.js-based languages (JavaScript, TypeScript).
 * Both languages use the same npm/node_modules ecosystem for dependency management.
 */
public final class NodeJsDependencyHelper {
    private static final Logger logger = LogManager.getLogger(NodeJsDependencyHelper.class);

    private NodeJsDependencyHelper() {
        // Utility class - prevent instantiation
    }

    /**
     * Scans for Node.js dependency candidates in a project's node_modules directory.
     * This method finds all package directories under node_modules, including scoped packages,
     * but excludes utility directories like .bin.
     *
     * @param project The project to scan for dependencies
     * @return List of paths to dependency candidate directories
     */
    public static List<Path> getDependencyCandidates(IProject project) {
        logger.debug("Scanning for Node.js dependency candidates in project: {}", project.getRoot());
        var results = new ArrayList<Path>();
        Path nodeModules = project.getRoot().resolve("node_modules");

        if (Files.isDirectory(nodeModules)) {
            logger.debug("Scanning node_modules directory: {}", nodeModules);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(nodeModules)) {
                for (Path entry : ds) {
                    String name = entry.getFileName().toString();
                    if (name.equals(".bin")) continue;  // skip executables

                    if (name.startsWith("@")) {        // scoped packages
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

    /**
     * Checks if the given path is likely already analyzed as part of the project's primary sources
     * for Node.js-based projects. This excludes node_modules dependencies since they are external.
     *
     * @param project The current project
     * @param pathToImport The absolute path to check
     * @return true if the path is considered part of the project's analyzed sources, false otherwise
     */
    public static boolean isAnalyzed(IProject project, Path pathToImport) {
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
}