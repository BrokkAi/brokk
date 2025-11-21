package ai.brokk.util;

import ai.brokk.IProject;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepoFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Utility class for dependency update operations.
 *
 * <p>This class provides static methods for reading/writing dependency metadata and
 * performing on-disk updates of imported dependencies (both local path and Git-backed).
 */
public final class DependencyUpdater {
    private static final Logger logger = LogManager.getLogger(DependencyUpdater.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static final String DEPENDENCY_METADATA_FILE = ".brokk-dependency.json";

    private DependencyUpdater() {
        // utility class
    }

    /**
     * Type of source from which a dependency was imported.
     */
    public enum DependencySourceType {
        LOCAL_PATH,
        GITHUB
    }

    /**
     * Metadata persisted alongside each imported dependency under its top-level directory.
     * Stored as JSON in a file named {@link #DEPENDENCY_METADATA_FILE}.
     */
    public record DependencyMetadata(
            DependencySourceType type,
            @Nullable String sourcePath,
            @Nullable String repoUrl,
            @Nullable String ref,
            long lastUpdatedMillis) {

        /**
         * Creates metadata for a dependency imported from a local directory.
         *
         * @param sourcePath absolute path to the original source directory
         */
        public static DependencyMetadata forLocalPath(Path sourcePath) {
            return new DependencyMetadata(
                    DependencySourceType.LOCAL_PATH,
                    sourcePath.toAbsolutePath().normalize().toString(),
                    null,
                    null,
                    System.currentTimeMillis());
        }

        /**
         * Creates metadata for a dependency imported from a Git repository.
         *
         * @param repoUrl normalized repository URL
         * @param ref branch or tag name used during import
         */
        public static DependencyMetadata forGit(String repoUrl, String ref) {
            return new DependencyMetadata(DependencySourceType.GITHUB, null, repoUrl, ref, System.currentTimeMillis());
        }
    }

    /**
     * Aggregated result for a multi-dependency auto-update pass.
     *
     * @param changedFiles set of files that changed across all updated dependencies
     * @param updatedDependencies number of dependencies that produced at least one changed file
     */
    public record DependencyAutoUpdateResult(Set<ProjectFile> changedFiles, int updatedDependencies) {}

    /**
     * Returns the path to the metadata file for a dependency rooted at {@code dependencyRoot}.
     */
    public static Path getDependencyMetadataPath(Path dependencyRoot) {
        return dependencyRoot.resolve(DEPENDENCY_METADATA_FILE);
    }

    /**
     * Returns the path to the metadata file for the given dependency {@link ProjectFile}.
     */
    public static Path getDependencyMetadataPath(ProjectFile dependencyRoot) {
        return getDependencyMetadataPath(dependencyRoot.absPath());
    }

    /**
     * Reads dependency metadata for a dependency rooted at {@code dependencyRoot}, if present.
     *
     * @param dependencyRoot absolute path to the dependency root directory
     * @return optional metadata, empty if the file is missing or malformed
     */
    public static Optional<DependencyMetadata> readDependencyMetadata(Path dependencyRoot) {
        var metadataPath = getDependencyMetadataPath(dependencyRoot);
        if (!Files.exists(metadataPath)) {
            return Optional.empty();
        }
        try (var reader = Files.newBufferedReader(metadataPath)) {
            var metadata = objectMapper.readValue(reader, DependencyMetadata.class);
            return Optional.of(metadata);
        } catch (Exception e) {
            logger.warn("Error reading dependency metadata from {}: {}", metadataPath, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Reads dependency metadata for the given dependency {@link ProjectFile}, if present.
     */
    public static Optional<DependencyMetadata> readDependencyMetadata(ProjectFile dependencyRoot) {
        return readDependencyMetadata(dependencyRoot.absPath());
    }

    /**
     * Writes metadata describing a dependency imported from a local directory.
     * Errors are logged but do not prevent the import from succeeding.
     */
    public static void writeLocalPathDependencyMetadata(Path dependencyRoot, Path sourcePath) {
        writeDependencyMetadata(dependencyRoot, DependencyMetadata.forLocalPath(sourcePath));
    }

    /**
     * Writes metadata describing a dependency imported from a Git repository.
     * Errors are logged but do not prevent the import from succeeding.
     */
    public static void writeGitDependencyMetadata(Path dependencyRoot, String repoUrl, String ref) {
        writeDependencyMetadata(dependencyRoot, DependencyMetadata.forGit(repoUrl, ref));
    }

    private static void writeDependencyMetadata(Path dependencyRoot, DependencyMetadata metadata) {
        var metadataPath = getDependencyMetadataPath(dependencyRoot);
        try {
            Files.createDirectories(dependencyRoot);
            String json = objectMapper.writeValueAsString(metadata);
            Files.writeString(metadataPath, json);
        } catch (Exception e) {
            logger.warn("Error writing dependency metadata to {}: {}", metadataPath, e.getMessage());
        }
    }

    private static boolean isCloneMarker(Path path) {
        String fileName = path.getFileName().toString();
        return CloneOperationTracker.CLONE_IN_PROGRESS_MARKER.equals(fileName)
                || CloneOperationTracker.CLONE_COMPLETE_MARKER.equals(fileName);
    }

    /**
     * Updates a dependency imported from a Git repository on disk using the metadata recorded
     * at import time.
     *
     * <p>This method:
     * <ul>
     *   <li>Clones the remote repo at the recorded URL and ref into a temporary directory.</li>
     *   <li>Removes any .git metadata from the temporary clone.</li>
     *   <li>Computes the union of files in the existing dependency directory
     *       and the new clone.</li>
     *   <li>Swaps the existing dependency directory contents with the updated clone.</li>
     *   <li>Refreshes the dependency metadata timestamp.</li>
     * </ul>
     *
     * <p>The returned {@link ProjectFile} set represents all files that may have changed as part of
     * the update. This method performs blocking I/O and must not be called on the Swing EDT.
     *
     * @param project the project containing the dependency
     * @param dependencyRoot top-level dependency directory as a {@link ProjectFile}
     * @param metadata parsed dependency metadata (must be of type GITHUB with non-null repoUrl/ref)
     * @return set of files that changed (added, removed, or modified) as a result of the update
     * @throws IllegalArgumentException if metadata is not of type GITHUB or missing required fields
     * @throws IOException if cloning or filesystem operations fail
     */
    @org.jetbrains.annotations.Blocking
    public static Set<ProjectFile> updateGitDependencyOnDisk(
            IProject project, ProjectFile dependencyRoot, DependencyMetadata metadata) throws IOException {
        if (metadata.type() != DependencySourceType.GITHUB) {
            throw new IllegalArgumentException("updateGitDependencyOnDisk requires GITHUB metadata");
        }
        String repoUrl = metadata.repoUrl();
        String ref = metadata.ref();
        if (repoUrl == null || ref == null) {
            throw new IllegalArgumentException("GITHUB metadata must contain repoUrl and ref");
        }

        Path targetPath = dependencyRoot.absPath();
        if (!Files.exists(targetPath) || !Files.isDirectory(targetPath)) {
            logger.warn(
                    "Git dependency root {} does not exist or is not a directory; treating as empty before update",
                    targetPath);
        }

        Path depsParent = targetPath.getParent();
        if (depsParent == null) {
            throw new IOException("Dependency root has no parent: " + targetPath);
        }

        String depName = targetPath.getFileName().toString();
        Path tempDir = Files.createTempDirectory(depsParent, depName + "-update-");

        // First, clone into an empty temporary directory. Only after a successful clone do we
        // create clone markers and register the operation, mirroring ImportDependencyDialog.
        try {
            // Clone latest version into the temporary directory (must be empty)
            GitRepoFactory.cloneRepo(repoUrl, tempDir, 1, ref);

            // Mark this clone as in-progress for cleanup purposes and register shutdown-hook tracking
            CloneOperationTracker.createInProgressMarker(tempDir, repoUrl, ref);
            CloneOperationTracker.registerCloneOperation(tempDir);
            try {
                // Remove any .git metadata from the temporary clone
                Path gitInternalDir = tempDir.resolve(".git");
                if (Files.exists(gitInternalDir)) {
                    FileUtil.deleteRecursively(gitInternalDir);
                }

                // Mark clone as complete once post-clone cleanup succeeds
                CloneOperationTracker.createCompleteMarker(tempDir, repoUrl, ref);
            } finally {
                CloneOperationTracker.unregisterCloneOperation(tempDir);
            }
        } catch (Exception e) {
            // Best-effort cleanup of the temporary clone directory; do not touch the existing dependency
            try {
                if (Files.exists(tempDir)) {
                    FileUtil.deleteRecursively(tempDir);
                }
            } catch (Exception cleanupEx) {
                logger.warn(
                        "Failed to cleanup temporary clone directory {} after failure: {}",
                        tempDir,
                        cleanupEx.getMessage());
            }
            throw new IOException("Failed to clone Git dependency from " + repoUrl + " at " + ref, e);
        }

        // At this point tempDir contains the updated tree. Compute changed files and swap.
        try {
            Path masterRoot = project.getMasterRootPathForConfig();

            var oldFiles = new HashSet<Path>();
            if (Files.exists(targetPath) && Files.isDirectory(targetPath)) {
                try (var pathStream = Files.walk(targetPath)) {
                    pathStream
                            .filter(Files::isRegularFile)
                            .filter(p -> !isCloneMarker(p))
                            .forEach(p -> oldFiles.add(masterRoot.relativize(p)));
                }
            }

            var newFiles = new HashSet<Path>();
            try (var pathStream = Files.walk(tempDir)) {
                pathStream
                        .filter(Files::isRegularFile)
                        .filter(p -> !isCloneMarker(p))
                        .forEach(p -> {
                            // Map temporary path to its eventual location under the real dependency root
                            Path relWithinTemp = tempDir.relativize(p);
                            Path futureLocation = targetPath.resolve(relWithinTemp);
                            newFiles.add(masterRoot.relativize(futureLocation));
                        });
            }

            // Union of old and new files: include all files that may have changed (added, removed, or modified)
            // Since we're doing a full directory swap, any file in either set should be re-indexed
            var allAffectedPaths = new HashSet<Path>(newFiles);
            allAffectedPaths.addAll(oldFiles);

            // Swap directories: remove old dependency directory and move new clone in its place
            if (Files.exists(targetPath)) {
                boolean deleted = FileUtil.deleteRecursively(targetPath);
                if (!deleted && Files.exists(targetPath)) {
                    throw new IOException("Failed to delete existing dependency directory " + targetPath);
                }
            }
            Files.move(tempDir, targetPath);

            // Refresh metadata timestamp for this dependency
            writeGitDependencyMetadata(targetPath, repoUrl, ref);

            return allAffectedPaths.stream()
                    .map(rel -> new ProjectFile(masterRoot, rel))
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            // On failure after clone, try to cleanup tempDir but do not touch the original directory
            try {
                if (Files.exists(tempDir)) {
                    FileUtil.deleteRecursively(tempDir);
                }
            } catch (Exception cleanupEx) {
                logger.warn(
                        "Failed to cleanup temporary clone directory {} after swap failure: {}",
                        tempDir,
                        cleanupEx.getMessage());
            }
            throw e;
        }
    }

    /**
     * Updates a dependency imported from a local path on disk using the metadata recorded
     * at import time.
     *
     * @param project the project containing the dependency
     * @param dependencyRoot top-level dependency directory as a {@link ProjectFile}
     * @param metadata parsed dependency metadata (must be of type LOCAL_PATH)
     * @return set of files that changed (added, removed, or modified) as a result of the update
     * @throws IllegalArgumentException if metadata is not of type LOCAL_PATH or missing required fields
     * @throws IOException if filesystem operations fail
     */
    @org.jetbrains.annotations.Blocking
    public static Set<ProjectFile> updateLocalPathDependencyOnDisk(
            IProject project, ProjectFile dependencyRoot, DependencyMetadata metadata) throws IOException {
        if (metadata.type() != DependencySourceType.LOCAL_PATH) {
            throw new IllegalArgumentException("updateLocalPathDependencyOnDisk requires LOCAL_PATH metadata");
        }
        String sourcePathStr = metadata.sourcePath();
        if (sourcePathStr == null || sourcePathStr.isBlank()) {
            throw new IllegalArgumentException("LOCAL_PATH metadata must contain non-empty sourcePath");
        }

        Path sourcePath = Path.of(sourcePathStr).toAbsolutePath().normalize();
        if (!Files.exists(sourcePath) || !Files.isDirectory(sourcePath)) {
            logger.warn("Local path dependency source {} does not exist or is not a directory", sourcePath);
            throw new IOException("Source directory for local dependency no longer exists: " + sourcePath);
        }

        Path targetPath = dependencyRoot.absPath();
        Path masterRoot = project.getMasterRootPathForConfig();
        Path dependenciesRoot = masterRoot.resolve(".brokk").resolve("dependencies");

        Path normalizedSource = sourcePath.normalize();
        Path normalizedDepsRoot = dependenciesRoot.normalize();
        Path normalizedTarget = targetPath.normalize();

        if (normalizedSource.startsWith(normalizedDepsRoot)) {
            String msg = "Local dependency source "
                    + normalizedSource
                    + " must not be inside dependencies root "
                    + normalizedDepsRoot;
            logger.warn(msg);
            throw new IOException(msg);
        }
        if (normalizedSource.startsWith(normalizedTarget) || normalizedTarget.startsWith(normalizedSource)) {
            String msg = "Local dependency source "
                    + normalizedSource
                    + " must not be the same as or inside its target directory "
                    + normalizedTarget;
            logger.warn(msg);
            throw new IOException(msg);
        }

        Path depsParent = normalizedTarget.getParent();
        if (depsParent == null) {
            throw new IOException("Dependency root has no parent: " + targetPath);
        }

        String depName = normalizedTarget.getFileName().toString();
        Path tempDir = Files.createTempDirectory(depsParent, depName + "-update-");

        try {
            // Copy allowed source files into a temporary directory, mirroring import behavior
            var allowedExtensions = project.getAnalyzerLanguages().stream()
                    .flatMap(lang -> lang.getExtensions().stream())
                    .map(ext -> ext.toLowerCase(Locale.ROOT))
                    .distinct()
                    .collect(Collectors.toList());

            java.nio.file.Files.walkFileTree(sourcePath, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(
                        Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    Path targetSubdir = tempDir.resolve(sourcePath.relativize(dir));
                    Files.createDirectories(targetSubdir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(
                        Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    String fileName = file.getFileName().toString();
                    int lastDot = fileName.lastIndexOf('.');
                    if (lastDot > 0 && lastDot < fileName.length() - 1) {
                        String ext = fileName.substring(lastDot + 1).toLowerCase(Locale.ROOT);
                        if (allowedExtensions.contains(ext)) {
                            Path dest = tempDir.resolve(sourcePath.relativize(file));
                            Files.copy(file, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });

            var oldFiles = new HashSet<Path>();
            if (Files.exists(targetPath) && Files.isDirectory(targetPath)) {
                try (var pathStream = Files.walk(targetPath)) {
                    pathStream.filter(Files::isRegularFile).forEach(p -> oldFiles.add(masterRoot.relativize(p)));
                }
            }

            var newFiles = new HashSet<Path>();
            try (var pathStream = Files.walk(tempDir)) {
                pathStream.filter(Files::isRegularFile).forEach(p -> {
                    Path relWithinTemp = tempDir.relativize(p);
                    Path futureLocation = targetPath.resolve(relWithinTemp);
                    newFiles.add(masterRoot.relativize(futureLocation));
                });
            }

            // Union of old and new files: include all files that may have changed (added, removed, or modified)
            // Since we're doing a full directory swap, any file in either set should be re-indexed
            var allAffectedPaths = new HashSet<Path>(newFiles);
            allAffectedPaths.addAll(oldFiles);

            if (Files.exists(targetPath)) {
                boolean deleted = FileUtil.deleteRecursively(targetPath);
                if (!deleted && Files.exists(targetPath)) {
                    throw new IOException("Failed to delete existing dependency directory " + targetPath);
                }
            }
            Files.move(tempDir, targetPath);

            writeLocalPathDependencyMetadata(targetPath, sourcePath);

            return allAffectedPaths.stream()
                    .map(rel -> new ProjectFile(masterRoot, rel))
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            try {
                if (Files.exists(tempDir)) {
                    FileUtil.deleteRecursively(tempDir);
                }
            } catch (Exception cleanupEx) {
                logger.warn(
                        "Failed to cleanup temporary local dependency directory {} after swap failure: {}",
                        tempDir,
                        cleanupEx.getMessage());
            }
            throw e;
        }
    }

    /**
     * Performs a single auto-update pass over all imported dependencies on disk.
     *
     * <p>This method:
     * <ul>
     *     <li>Enumerates all dependency roots under .brokk/dependencies.</li>
     *     <li>Reads {@link DependencyMetadata} for each dependency, if present.</li>
     *     <li>Invokes {@link #updateLocalPathDependencyOnDisk} for
     *         LOCAL_PATH dependencies when {@code includeLocal} is true.</li>
     *     <li>Invokes {@link #updateGitDependencyOnDisk} for
     *         GITHUB dependencies when {@code includeGit} is true.</li>
     *     <li>Aggregates the union of changed files and counts how many dependencies produced
     *         at least one change.</li>
     * </ul>
     *
     * <p>Errors updating an individual dependency are logged and do not abort the overall pass.
     * Dependencies without metadata, or with unsupported types, are skipped.
     *
     * <p>This method is blocking and performs filesystem and (for Git) network I/O. Callers
     * should invoke it off the EDT and are responsible for notifying the analyzer by calling
     * {@code AnalyzerWrapper.updateFiles(result.changedFiles())} if desired.
     *
     * @param project the project to update dependencies for
     * @param includeLocal whether to auto-update LOCAL_PATH dependencies
     * @param includeGit whether to auto-update GITHUB dependencies
     * @return aggregated result of the auto-update pass
     */
    @org.jetbrains.annotations.Blocking
    public static DependencyAutoUpdateResult autoUpdateDependenciesOnce(
            IProject project, boolean includeLocal, boolean includeGit) {
        var changedFiles = new HashSet<ProjectFile>();
        int updatedDependencies = 0;

        if (!includeLocal && !includeGit) {
            return new DependencyAutoUpdateResult(Set.of(), 0);
        }

        var allDeps = project.getAllOnDiskDependencies();
        if (allDeps.isEmpty()) {
            return new DependencyAutoUpdateResult(Set.of(), 0);
        }

        for (var depRoot : allDeps) {
            var metadataOpt = readDependencyMetadata(depRoot);
            if (metadataOpt.isEmpty()) {
                continue;
            }
            var metadata = metadataOpt.get();

            boolean isLocal = metadata.type() == DependencySourceType.LOCAL_PATH;
            boolean isGit = metadata.type() == DependencySourceType.GITHUB;

            if ((isLocal && !includeLocal) || (isGit && !includeGit)) {
                continue;
            }
            if (!isLocal && !isGit) {
                // Unknown/unsupported type: ignore
                continue;
            }

            try {
                Set<ProjectFile> delta;
                if (isLocal) {
                    delta = updateLocalPathDependencyOnDisk(project, depRoot, metadata);
                } else {
                    delta = updateGitDependencyOnDisk(project, depRoot, metadata);
                }
                if (!delta.isEmpty()) {
                    updatedDependencies++;
                    changedFiles.addAll(delta);
                }
            } catch (IOException e) {
                logger.warn(
                        "Failed to auto-update dependency {} of type {}: {}",
                        depRoot.absPath(),
                        metadata.type(),
                        e.getMessage());
            }
        }

        if (changedFiles.isEmpty()) {
            return new DependencyAutoUpdateResult(Set.of(), 0);
        }
        return new DependencyAutoUpdateResult(Collections.unmodifiableSet(changedFiles), updatedDependencies);
    }
}
