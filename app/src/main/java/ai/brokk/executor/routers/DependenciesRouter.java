package ai.brokk.executor.routers;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.NodeJsDependencyHelper;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.executor.jobs.ErrorPayload;
import ai.brokk.util.DependencyUpdater;
import ai.brokk.util.DependencyUpdater.DependencyMetadata;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
            if (method.equals("GET")) {
                handleGetDependencies(exchange);
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
}
