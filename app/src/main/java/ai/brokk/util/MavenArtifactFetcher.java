package ai.brokk.util;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transfer.AbstractTransferListener;
import org.eclipse.aether.transfer.TransferEvent;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.jetbrains.annotations.Nullable;

public class MavenArtifactFetcher {
    private static final Logger logger = LogManager.getLogger(MavenArtifactFetcher.class);

    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repositories;

    public MavenArtifactFetcher() {
        this(null);
    }

    public MavenArtifactFetcher(@Nullable DownloadProgressListener progressListener) {
        this.system = newRepositorySystem();
        this.session = newRepositorySystemSession(system, progressListener);
        this.repositories = List.of(
                new RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2/").build());
    }

    @SuppressWarnings("deprecation")
    private static RepositorySystem newRepositorySystem() {
        var locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
        locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {
            @Override
            public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
                logger.error("Service creation failed for type {} with implementation {}", type, impl, exception);
            }
        });
        return locator.getService(RepositorySystem.class);
    }

    @SuppressWarnings("deprecation")
    private static RepositorySystemSession newRepositorySystemSession(
            RepositorySystem system, @Nullable DownloadProgressListener progressListener) {
        var session = MavenRepositorySystemUtils.newSession();
        var localRepoPath = Path.of(System.getProperty("user.home"), ".m2", "repository");
        var localRepo = new LocalRepository(localRepoPath.toFile());
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
        session.setTransferListener(new ProgressReportingTransferListener(progressListener));
        return session;
    }

    public Optional<Path> fetch(String coordinates, @Nullable String classifier) {
        Artifact artifact;
        try {
            var baseArtifact = new DefaultArtifact(coordinates);
            if (classifier != null && !classifier.isBlank()) {
                artifact = new DefaultArtifact(
                        baseArtifact.getGroupId(),
                        baseArtifact.getArtifactId(),
                        classifier,
                        baseArtifact.getExtension(),
                        baseArtifact.getVersion());
            } else {
                artifact = baseArtifact;
            }
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid artifact coordinates: {}", coordinates, e);
            return Optional.empty();
        }

        var artifactRequest = new ArtifactRequest();
        artifactRequest.setArtifact(artifact);
        artifactRequest.setRepositories(repositories);

        try {
            logger.info("Resolving artifact: {}", artifact);
            ArtifactResult artifactResult = system.resolveArtifact(session, artifactRequest);
            Path filePath = artifactResult.getArtifact().getFile().toPath();
            logger.info("Resolved artifact {} to {}", artifact, filePath);
            return Optional.of(filePath);
        } catch (ArtifactResolutionException e) {
            if (e.getResults().stream().anyMatch(ArtifactResult::isMissing)) {
                logger.info("Artifact not found: {}", artifact);
            } else {
                logger.warn("Could not resolve artifact: {}", artifact, e);
            }
            return Optional.empty();
        }
    }

    /**
     * Resolve the latest version for a groupId:artifactId from Maven Central.
     * Uses the Maven Central REST API.
     *
     * @param groupId the Maven groupId
     * @param artifactId the Maven artifactId
     * @return the latest version if found, empty otherwise
     */
    public Optional<String> resolveLatestVersion(String groupId, String artifactId) {
        var query = "g:%s AND a:%s".formatted(groupId, artifactId);
        var url = "https://search.maven.org/solrsearch/select?q=%s&rows=1&wt=json"
                .formatted(URLEncoder.encode(query, StandardCharsets.UTF_8));

        try {
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            logger.debug("Querying Maven Central for latest version: {}", url);
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.warn("Maven Central returned status {}", response.statusCode());
                return Optional.empty();
            }

            var json = Json.getMapper().readTree(response.body());
            var docs = json.path("response").path("docs");
            if (docs.isArray() && !docs.isEmpty()) {
                var latestVersion = docs.get(0).path("latestVersion").asText(null);
                if (latestVersion != null && !latestVersion.isBlank()) {
                    logger.info("Resolved latest version for {}:{} -> {}", groupId, artifactId, latestVersion);
                    return Optional.of(latestVersion);
                }
            }

            logger.info("No version found for {}:{}", groupId, artifactId);
            return Optional.empty();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("Failed to query Maven Central for {}:{}: {}", groupId, artifactId, e.getMessage());
            return Optional.empty();
        }
    }

    private static class ProgressReportingTransferListener extends AbstractTransferListener {
        private static final long THROTTLE_MS = 250;

        private final @Nullable DownloadProgressListener progressListener;
        private volatile long lastUpdateTime = 0;

        ProgressReportingTransferListener(@Nullable DownloadProgressListener progressListener) {
            this.progressListener = progressListener;
        }

        @Override
        public void transferInitiated(TransferEvent event) {
            var resource = event.getResource();
            logger.info("Downloading {}{}", resource.getRepositoryUrl(), resource.getResourceName());
            if (progressListener != null) {
                progressListener.onProgress(resource.getResourceName(), 0, resource.getContentLength());
            }
        }

        @Override
        public void transferProgressed(TransferEvent event) {
            if (progressListener == null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastUpdateTime < THROTTLE_MS) {
                return;
            }
            lastUpdateTime = now;

            var resource = event.getResource();
            progressListener.onProgress(
                    resource.getResourceName(), event.getTransferredBytes(), resource.getContentLength());
        }

        @Override
        public void transferSucceeded(TransferEvent event) {
            var resource = event.getResource();
            logger.info("Download complete for {}{}", resource.getRepositoryUrl(), resource.getResourceName());
            if (progressListener != null) {
                progressListener.onProgress(
                        resource.getResourceName(), event.getTransferredBytes(), event.getTransferredBytes());
            }
        }

        @Override
        public void transferFailed(TransferEvent event) {
            var resource = event.getResource();
            logger.warn(
                    "Download failed for {}{}",
                    resource.getRepositoryUrl(),
                    resource.getResourceName(),
                    event.getException());
        }
    }
}
