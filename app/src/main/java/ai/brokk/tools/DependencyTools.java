package ai.brokk.tools;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.Languages;
import ai.brokk.project.IProject;
import ai.brokk.util.Decompiler;
import ai.brokk.util.DownloadProgressListener;
import ai.brokk.util.MavenArtifactFetcher;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.jetbrains.annotations.Blocking;

/**
 * Tools for discovering and importing dependencies.
 * Designed for use by ArchitectAgent during the exploration phase.
 */
public class DependencyTools {
    private static final Logger logger = LogManager.getLogger(DependencyTools.class);

    private final IContextManager contextManager;
    private final MavenArtifactFetcher fetcher;

    public DependencyTools(IContextManager cm) {
        this(cm, new MavenArtifactFetcher(createProgressListener(cm.getIo())));
    }

    public DependencyTools(IContextManager cm, MavenArtifactFetcher fetcher) {
        this.contextManager = cm;
        this.fetcher = fetcher;
    }

    private static DownloadProgressListener createProgressListener(IConsoleIO io) {
        return (artifactName, transferred, total) -> {
            String filename = artifactName;
            int lastSlash = artifactName.lastIndexOf('/');
            if (lastSlash != -1 && lastSlash < artifactName.length() - 1) {
                filename = artifactName.substring(lastSlash + 1);
            }

            String message;
            if (total > 0) {
                int percent = (int) (100 * transferred / total);
                message = "Downloading %s... %d%%".formatted(filename, percent);
            } else {
                message = "Downloading %s... %.1f MB".formatted(filename, transferred / 1_048_576.0);
            }
            io.showNotification(IConsoleIO.NotificationRole.INFO, message);
        };
    }

    /**
     * Returns true if this tool is supported for the given project.
     * Maven dependency import is only supported for Java projects.
     */
    public static boolean isSupported(IProject project) {
        return project.getAnalyzerLanguages().contains(Languages.JAVA);
    }

    @Blocking
    @Tool("Import a Java dependency from Maven Central into Code Intelligence. "
            + "The library will be downloaded, decompiled, and added to the analyzer. "
            + "Use this when you need to understand or reference external library code.")
    public String importMavenDependency(
            @P(
                            "Maven coordinates: 'groupId:artifactId:version' or 'groupId:artifactId' for latest. "
                                    + "Examples: 'com.google.guava:guava:32.1.2-jre', 'com.fasterxml.jackson.core:jackson-databind'")
                    String coordinates)
            throws InterruptedException {

        logger.info("importMavenDependency called with: {}", coordinates);
        var io = contextManager.getIo();

        // Check for early cancellation
        checkInterrupted();

        // Count colons to validate format (only accept g:a or g:a:v)
        long colonCount = coordinates.chars().filter(c -> c == ':').count();
        if (colonCount < 1 || colonCount > 2) {
            logger.warn("Invalid coordinates format: {}", coordinates);
            return "Invalid coordinates format. Expected 'groupId:artifactId' or 'groupId:artifactId:version'. "
                    + "Examples: 'com.google.guava:guava' or 'com.google.guava:guava:32.1.2-jre'";
        }

        // Parse coordinates using Maven's DefaultArtifact
        // For two-part coords (g:a), append placeholder version since DefaultArtifact requires it
        String coordsToParse = colonCount == 1 ? coordinates + ":LATEST" : coordinates;
        DefaultArtifact artifact;
        try {
            artifact = new DefaultArtifact(coordsToParse);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid coordinates format: {}", coordinates);
            return "Invalid coordinates format. Expected 'groupId:artifactId' or 'groupId:artifactId:version'. "
                    + "Examples: 'com.google.guava:guava' or 'com.google.guava:guava:32.1.2-jre'";
        }

        var groupId = artifact.getGroupId();
        var artifactId = artifact.getArtifactId();
        String version = colonCount == 1 ? null : artifact.getVersion();

        // Resolve latest version if not provided
        if (version == null || version.isEmpty()) {
            // Resolve latest version from Maven Central
            io.showNotification(
                    IConsoleIO.NotificationRole.INFO,
                    "Resolving latest version for " + groupId + ":" + artifactId + "...");
            logger.info("Resolving latest version for {}:{} from Maven Central", groupId, artifactId);
            var latestOpt = fetcher.resolveLatestVersion(groupId, artifactId);
            if (latestOpt.isEmpty()) {
                logger.warn("Could not resolve latest version for {}:{}", groupId, artifactId);
                return ("Could not resolve latest version for %s:%s from Maven Central. "
                                + "Try specifying an explicit version.")
                        .formatted(groupId, artifactId);
            }
            version = latestOpt.get();
            logger.info("Resolved latest version: {}", version);
        }

        var fullCoordinates = "%s:%s:%s".formatted(groupId, artifactId, version);

        // Check for cancellation after version resolution
        checkInterrupted();

        // Download JAR
        io.showNotification(IConsoleIO.NotificationRole.INFO, "Downloading " + fullCoordinates + "...");
        logger.info("Fetching artifact: {}", fullCoordinates);
        var jarPathOpt = fetcher.fetch(fullCoordinates, null);
        if (jarPathOpt.isEmpty()) {
            logger.warn("Artifact not found on Maven Central: {}", fullCoordinates);
            return "Could not find artifact %s on Maven Central. Check the coordinates and try again."
                    .formatted(fullCoordinates);
        }

        var jarPath = jarPathOpt.get();
        logger.debug("JAR downloaded to: {}", jarPath);
        // Use getMasterRootPathForConfig() for worktree compatibility - dependencies are shared
        var projectRoot = contextManager.getProject().getMasterRootPathForConfig();

        // Check for cancellation before decompilation (longest phase)
        checkInterrupted();

        // Decompile/extract to .brokk/dependencies/
        io.showNotification(
                IConsoleIO.NotificationRole.INFO,
                "Importing " + artifactId + " (this may take a moment for large libraries)...");
        logger.info("Importing JAR to {}", projectRoot.resolve(".brokk/dependencies"));
        var resultOpt = Decompiler.decompileJarBlocking(jarPath, projectRoot, false);
        if (resultOpt.isEmpty()) {
            logger.error("Decompile failed for {}", fullCoordinates);
            return "Failed to import %s. Check logs for details.".formatted(fullCoordinates);
        }

        var result = resultOpt.get();
        logger.info("Import complete: {} files extracted", result.filesExtracted());
        var relativeOutput = projectRoot.relativize(result.outputDir());
        var sourceInfo = result.usedSources() ? " (from sources JAR)" : " (decompiled)";

        // Check for cancellation before analyzer registration
        checkInterrupted();

        // Register the dependency with the project so the analyzer indexes it
        String depName = result.outputDir().getFileName().toString();
        String intelligenceStatus;
        try {
            io.showNotification(IConsoleIO.NotificationRole.INFO, "Adding " + depName + " to Code Intelligence...");
            logger.debug("Adding {} to live dependencies...", depName);
            var analyzerWrapper = contextManager.getAnalyzerWrapper();
            contextManager
                    .getProject()
                    .addLiveDependency(depName, analyzerWrapper)
                    .orTimeout(60, TimeUnit.SECONDS)
                    .join();
            logger.info("Successfully added {} to live dependencies", depName);
            contextManager.notifyLiveDependenciesChanged();
            intelligenceStatus = "The library has been added to live dependencies and Code Intelligence is updating.";
        } catch (Exception e) {
            logger.error("Failed to add live dependency: {}", depName, e);
            contextManager.requestRebuild();
            intelligenceStatus = "A Code Intelligence rebuild was requested and will update shortly.";
        }

        return "Successfully imported %s to %s (%d Java files%s). %s"
                .formatted(fullCoordinates, relativeOutput, result.filesExtracted(), sourceInfo, intelligenceStatus);
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Import cancelled");
        }
    }
}
