package ai.brokk.tools;

import ai.brokk.IContextManager;
import ai.brokk.util.Decompiler;
import ai.brokk.util.MavenArtifactFetcher;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;

/**
 * Tools for discovering and importing dependencies.
 * Designed for use by ArchitectAgent during the exploration phase.
 */
public class DependencyTools {
    private static final Logger logger = LogManager.getLogger(DependencyTools.class);

    private final IContextManager contextManager;

    public DependencyTools(IContextManager cm) {
        this.contextManager = cm;
    }

    @Blocking
    @Tool("Import a Java dependency from Maven Central into Code Intelligence. "
            + "The library will be downloaded, decompiled, and added to the analyzer. "
            + "Use this when you need to understand or reference external library code.")
    public String importMavenDependency(
            @P(
                            "Maven coordinates: 'groupId:artifactId:version' or 'groupId:artifactId' for latest. "
                                    + "Examples: 'com.google.guava:guava:32.1.2-jre', 'com.fasterxml.jackson.core:jackson-databind'")
                    String coordinates) {

        System.out.println("[DependencyTools] importMavenDependency called with: " + coordinates);

        // Parse coordinates
        var parts = coordinates.split(":");
        if (parts.length < 2 || parts.length > 3) {
            System.out.println("[DependencyTools] ERROR: Invalid coordinates format");
            return "Invalid coordinates format. Expected 'groupId:artifactId' or 'groupId:artifactId:version'. "
                    + "Examples: 'com.google.guava:guava' or 'com.google.guava:guava:32.1.2-jre'";
        }

        var groupId = parts[0].trim();
        var artifactId = parts[1].trim();
        String version;
        var fetcher = new MavenArtifactFetcher();

        if (parts.length == 3) {
            version = parts[2].trim();
            System.out.println("[DependencyTools] Using provided version: " + version);
        } else {
            // Resolve latest version from Maven Central
            System.out.println("[DependencyTools] No version specified, resolving latest from Maven Central...");
            logger.info("No version specified for {}:{}, resolving latest from Maven Central", groupId, artifactId);
            var latestOpt = fetcher.resolveLatestVersion(groupId, artifactId);
            if (latestOpt.isEmpty()) {
                System.out.println("[DependencyTools] ERROR: Could not resolve latest version");
                return "Could not resolve latest version for %s:%s from Maven Central. "
                        + "Try specifying an explicit version.".formatted(groupId, artifactId);
            }
            version = latestOpt.get();
            System.out.println("[DependencyTools] Resolved latest version: " + version);
            logger.info("Resolved latest version: {}", version);
        }

        var fullCoordinates = "%s:%s:%s".formatted(groupId, artifactId, version);
        System.out.println("[DependencyTools] Full coordinates: " + fullCoordinates);

        // Download JAR
        System.out.println("[DependencyTools] Fetching JAR from Maven Central...");
        logger.info("Fetching artifact: {}", fullCoordinates);
        var jarPathOpt = fetcher.fetch(fullCoordinates, null);
        if (jarPathOpt.isEmpty()) {
            System.out.println("[DependencyTools] ERROR: Could not find artifact on Maven Central");
            return "Could not find artifact %s on Maven Central. "
                    + "Check the coordinates and try again.".formatted(fullCoordinates);
        }

        var jarPath = jarPathOpt.get();
        System.out.println("[DependencyTools] JAR downloaded to: " + jarPath);
        var projectRoot = contextManager.getProject().getRoot();

        // Decompile/extract to .brokk/dependencies/
        System.out.println("[DependencyTools] Starting decompile/extract to .brokk/dependencies/...");
        logger.info("Importing JAR: {}", jarPath);
        var resultOpt = Decompiler.decompileJarBlocking(jarPath, projectRoot, false);
        if (resultOpt.isEmpty()) {
            System.out.println("[DependencyTools] ERROR: Decompile failed");
            return "Failed to import %s. Check logs for details.".formatted(fullCoordinates);
        }

        var result = resultOpt.get();
        System.out.println("[DependencyTools] Decompile complete: " + result.filesExtracted() + " files");
        var relativeOutput = projectRoot.relativize(result.outputDir());
        var sourceInfo = result.usedSources() ? " (from sources JAR)" : " (decompiled)";

        // Register the dependency with the project so the analyzer indexes it
        String depName = result.outputDir().getFileName().toString();
        String intelligenceStatus;
        try {
            System.out.println("[DependencyTools] Adding " + depName + " to live dependencies...");
            contextManager.getProject().addLiveDependency(depName, contextManager.getAnalyzerWrapper()).join();
            intelligenceStatus = "The library has been added to live dependencies and Code Intelligence is updating.";
        } catch (Exception e) {
            logger.error("Failed to add live dependency: {}", depName, e);
            System.out.println("[DependencyTools] ERROR: Failed to add live dependency, requesting rebuild fallback");
            contextManager.requestRebuild();
            intelligenceStatus = "A Code Intelligence rebuild was requested and will update shortly.";
        }

        return "Successfully imported %s to %s (%d Java files%s). %s"
                .formatted(fullCoordinates, relativeOutput, result.filesExtracted(), sourceInfo, intelligenceStatus);
    }
}
