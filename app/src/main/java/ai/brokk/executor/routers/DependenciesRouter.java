package ai.brokk.executor.routers;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.NodeJsDependencyHelper;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.project.AbstractProject;
import ai.brokk.util.DependencyUpdater;
import ai.brokk.util.DependencyUpdater.DependencyMetadata;
import ai.brokk.util.DependencyUpdater.DependencySourceType;
import ai.brokk.util.FileUtil;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Router for /v1/dependencies endpoints.
 */
@NullMarked
public final class DependenciesRouter implements SimpleHttpServer.CheckedHttpHandler {
    private static final Logger logger = LogManager.getLogger(DependenciesRouter.class);

    private final ContextManager contextManager;

    public DependenciesRouter(ContextManager contextManager) {
        this.contextManager = contextManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws Exception {
        var method = exchange.getRequestMethod();
        var path = exchange.getRequestURI().getPath();
        var normalizedPath = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;

        if (normalizedPath.equals("/v1/dependencies")) {
            switch (method) {
                case "GET" -> handleGetDependencies(exchange);
                case "PUT" -> handlePutDependencies(exchange);
                default -> RouterUtil.sendMethodNotAllowed(exchange);
            }
            return;
        }

        // Check for /v1/dependencies/{name}/update pattern
        if (normalizedPath.startsWith("/v1/dependencies/") && normalizedPath.endsWith("/update")) {
            if (method.equals("POST")) {
                handlePostDependencyUpdate(exchange, normalizedPath);
            } else {
                RouterUtil.sendMethodNotAllowed(exchange);
            }
            return;
        }

        // Check for /v1/dependencies/{name} pattern (for DELETE)
        if (normalizedPath.startsWith("/v1/dependencies/")) {
            if (method.equals("DELETE")) {
                handleDeleteDependency(exchange, normalizedPath);
            } else {
                RouterUtil.sendMethodNotAllowed(exchange);
            }
            return;
        }

        SimpleHttpServer.sendJsonResponse(exchange, 404, ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Not found"));
    }

    private void handleGetDependencies(HttpExchange exchange) throws IOException {
        try {
            var project = contextManager.getProject();
            project.reloadWorkspaceProperties();
            var allDeps = project.getAllOnDiskDependencies();
            var liveDeps = project.getLiveDependencies();

            // Build a set of live dependency directory names for quick lookup
            Set<String> liveDepNames = liveDeps.stream()
                    .map(dep -> dep.root().absPath().getFileName().toString())
                    .collect(Collectors.toSet());

            var dependencyList = new ArrayList<Map<String, Object>>();

            for (var depRoot : allDeps) {
                var depMap = buildDependencyInfo(depRoot, liveDepNames);
                dependencyList.add(depMap);
            }

            SimpleHttpServer.sendJsonResponse(exchange, Map.of("dependencies", dependencyList));
        } catch (Exception e) {
            logger.error("Error handling GET /v1/dependencies", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to retrieve dependencies", e));
        }
    }

    private Map<String, Object> buildDependencyInfo(ProjectFile depRoot, Set<String> liveDepNames) {
        var depMap = new HashMap<String, Object>();
        Path depPath = depRoot.absPath();
        String name = depPath.getFileName().toString();

        depMap.put("name", name);
        depMap.put("isLive", liveDepNames.contains(name));

        // Compute display name - check for Node.js package.json
        String displayName = computeDisplayName(depPath, name);
        depMap.put("displayName", displayName);

        // Count files in the dependency directory
        long fileCount = countFiles(depPath);
        depMap.put("fileCount", fileCount);

        // Read and include metadata if present
        var metadataOpt = DependencyUpdater.readDependencyMetadata(depRoot);
        if (metadataOpt.isPresent()) {
            depMap.put("metadata", buildMetadataMap(metadataOpt.get()));
        } else {
            depMap.put("metadata", null);
        }

        return depMap;
    }

    private String computeDisplayName(Path depPath, String fallbackName) {
        // Check for Node.js package.json
        var nodePackage = NodeJsDependencyHelper.readPackageJsonFromDir(depPath);
        if (nodePackage != null) {
            String nodeDisplayName = NodeJsDependencyHelper.displayNameFrom(nodePackage);
            if (!nodeDisplayName.isEmpty()) {
                return nodeDisplayName;
            }
        }
        return fallbackName;
    }

    private long countFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (var stream = Files.walk(directory)) {
            return stream.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            logger.debug("Error counting files in {}: {}", directory, e.getMessage());
            return 0;
        }
    }

    private Map<String, @Nullable Object> buildMetadataMap(DependencyMetadata metadata) {
        var map = new HashMap<String, @Nullable Object>();
        map.put("type", metadata.type().name());
        map.put("sourcePath", metadata.sourcePath());
        map.put("repoUrl", metadata.repoUrl());
        map.put("ref", metadata.ref());
        map.put("commitHash", metadata.commitHash());
        map.put("lastUpdatedMillis", metadata.lastUpdatedMillis());
        return map;
    }

    // ── PUT /v1/dependencies ──────────────────────────────

    private void handlePutDependencies(HttpExchange exchange) throws IOException {
        var request = RouterUtil.parseJsonOr400(exchange, UpdateLiveDepsRequest.class, "/v1/dependencies");
        if (request == null) return;

        var names = request.liveDependencyNames();
        if (names == null) {
            RouterUtil.sendValidationError(exchange, "liveDependencyNames must not be null");
            return;
        }

        try {
            var project = contextManager.getProject();
            var masterRoot = project.getMasterRootPathForConfig();
            var dependenciesDir =
                    masterRoot.resolve(AbstractProject.BROKK_DIR).resolve(AbstractProject.DEPENDENCIES_DIR);

            // Convert names to absolute paths
            var newLiveDependencyDirs = new HashSet<Path>();
            for (var name : names) {
                if (name != null && !name.isBlank()) {
                    var resolved = dependenciesDir.resolve(name.strip());
                    if (!resolved.normalize().startsWith(dependenciesDir.normalize())) {
                        logger.warn("Skipping path-traversal dependency name: {}", name);
                        continue;
                    }
                    newLiveDependencyDirs.add(resolved);
                }
            }

            // Call updateLiveDependencies with null analyzer (headless mode)
            project.updateLiveDependencies(newLiveDependencyDirs, null).join();

            SimpleHttpServer.sendJsonResponse(
                    exchange, Map.of("status", "updated", "liveCount", newLiveDependencyDirs.size()));
        } catch (Exception e) {
            logger.error("Error handling PUT /v1/dependencies", e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to update live dependencies", e));
        }
    }

    // ── POST /v1/dependencies/{name}/update ───────────────

    private void handlePostDependencyUpdate(HttpExchange exchange, String normalizedPath) throws IOException {
        // Extract dependency name from path: /v1/dependencies/{name}/update
        var prefix = "/v1/dependencies/";
        var suffix = "/update";
        var encodedName = normalizedPath.substring(prefix.length(), normalizedPath.length() - suffix.length());

        if (encodedName.isBlank()) {
            RouterUtil.sendValidationError(exchange, "dependency name is required");
            return;
        }

        String depName;
        try {
            depName = URLDecoder.decode(encodedName, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            RouterUtil.sendValidationError(exchange, "dependency name is not valid URL encoding");
            return;
        }

        try {
            var project = contextManager.getProject();
            var allDeps = project.getAllOnDiskDependencies();

            // Find the dependency by name
            var depRootOpt = allDeps.stream()
                    .filter(dep -> dep.absPath().getFileName().toString().equals(depName))
                    .findFirst();

            if (depRootOpt.isEmpty()) {
                SimpleHttpServer.sendJsonResponse(
                        exchange,
                        404,
                        ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Dependency not found: " + depName));
                return;
            }

            var depRoot = depRootOpt.get();
            var metadataOpt = DependencyUpdater.readDependencyMetadata(depRoot);

            if (metadataOpt.isEmpty()) {
                RouterUtil.sendValidationError(exchange, "No metadata found for dependency: " + depName);
                return;
            }

            var metadata = metadataOpt.get();
            Set<ProjectFile> changedFiles;

            if (metadata.type() == DependencySourceType.LOCAL_PATH) {
                changedFiles = DependencyUpdater.updateLocalPathDependencyOnDisk(project, depRoot, metadata);
            } else if (metadata.type() == DependencySourceType.GITHUB) {
                changedFiles = DependencyUpdater.updateGitDependencyOnDisk(project, depRoot, metadata);
            } else {
                RouterUtil.sendValidationError(exchange, "Unsupported dependency type: " + metadata.type());
                return;
            }

            SimpleHttpServer.sendJsonResponse(
                    exchange, Map.of("status", "updated", "changedFiles", changedFiles.size()));
        } catch (Exception e) {
            logger.error("Error handling POST /v1/dependencies/{}/update", depName, e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to update dependency", e));
        }
    }

    // ── DELETE /v1/dependencies/{name} ────────────────────

    private void handleDeleteDependency(HttpExchange exchange, String normalizedPath) throws IOException {
        // Extract dependency name from path: /v1/dependencies/{name}
        var prefix = "/v1/dependencies/";
        var encodedName = normalizedPath.substring(prefix.length());

        if (encodedName.isBlank()) {
            RouterUtil.sendValidationError(exchange, "dependency name is required");
            return;
        }

        String depName;
        try {
            depName = URLDecoder.decode(encodedName, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            RouterUtil.sendValidationError(exchange, "dependency name is not valid URL encoding");
            return;
        }

        try {
            var project = contextManager.getProject();
            var masterRoot = project.getMasterRootPathForConfig();
            var dependenciesDir =
                    masterRoot.resolve(AbstractProject.BROKK_DIR).resolve(AbstractProject.DEPENDENCIES_DIR);
            var depPath = dependenciesDir.resolve(depName);

            if (!depPath.normalize().startsWith(dependenciesDir.normalize())) {
                RouterUtil.sendValidationError(exchange, "Invalid dependency name: " + depName);
                return;
            }

            if (!Files.exists(depPath) || !Files.isDirectory(depPath)) {
                SimpleHttpServer.sendJsonResponse(
                        exchange,
                        404,
                        ErrorPayload.of(ErrorPayload.Code.NOT_FOUND, "Dependency not found: " + depName));
                return;
            }

            // Remove from live set first
            var currentLiveDirs = project.getLiveDependencies().stream()
                    .map(dep -> dep.root().absPath())
                    .collect(Collectors.toSet());

            var newLiveDirs = new HashSet<>(currentLiveDirs);
            newLiveDirs.remove(depPath);

            project.updateLiveDependencies(newLiveDirs, null).join();

            // Delete the directory
            FileUtil.deleteRecursively(depPath);

            SimpleHttpServer.sendJsonResponse(exchange, Map.of("status", "deleted", "name", depName));
        } catch (Exception e) {
            logger.error("Error handling DELETE /v1/dependencies/{}", depName, e);
            SimpleHttpServer.sendJsonResponse(
                    exchange, 500, ErrorPayload.internalError("Failed to delete dependency", e));
        }
    }

    // ── Request DTOs ──────────────────────────────────────

    private record UpdateLiveDepsRequest(@Nullable List<String> liveDependencyNames) {}
}
