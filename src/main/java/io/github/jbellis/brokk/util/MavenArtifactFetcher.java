package io.github.jbellis.brokk.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
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
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.transfer.AbstractTransferListener;
import org.eclipse.aether.transfer.TransferEvent;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class MavenArtifactFetcher
{
    private static final Logger logger = LogManager.getLogger(MavenArtifactFetcher.class);

    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repositories;

    public MavenArtifactFetcher() {
        this.system = newRepositorySystem();
        this.session = newRepositorySystemSession(system);
        this.repositories = List.of(new RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2/").build());
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
    private static RepositorySystemSession newRepositorySystemSession(RepositorySystem system) {
        var session = MavenRepositorySystemUtils.newSession();
        var localRepoPath = Path.of(System.getProperty("user.home"), ".m2", "repository");
        var localRepo = new LocalRepository(localRepoPath.toFile());
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
        session.setTransferListener(new LoggingTransferListener());
        return session;
    }

    public Optional<Path> fetch(String coordinates, @Nullable String classifier) {
        Artifact artifact;
        try {
            var baseArtifact = new DefaultArtifact(coordinates);
            if (classifier != null && !classifier.isBlank()) {
                artifact = new DefaultArtifact(baseArtifact.getGroupId(),
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

    private static class LoggingTransferListener extends AbstractTransferListener
    {
        @Override
        public void transferInitiated(TransferEvent event) {
            var resource = event.getResource();
            logger.info("Downloading {}{}", resource.getRepositoryUrl(), resource.getResourceName());
        }

        @Override
        public void transferSucceeded(TransferEvent event) {
            var resource = event.getResource();
            logger.info("Download complete for {}{}", resource.getRepositoryUrl(), resource.getResourceName());
        }

        @Override
        public void transferFailed(TransferEvent event) {
            var resource = event.getResource();
            logger.warn("Download failed for {}{}", resource.getRepositoryUrl(), resource.getResourceName(), event.getException());
        }
    }
}
