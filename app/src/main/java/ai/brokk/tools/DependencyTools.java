package ai.brokk.tools;

import ai.brokk.IContextManager;
import ai.brokk.util.Decompiler;
import ai.brokk.util.MavenArtifactFetcher;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

    @Tool("Import a Java dependency from Maven Central into Code Intelligence. " +
          "The library will be downloaded, decompiled, and added to the analyzer. " +
          "Use this when you need to understand or reference external library code.")
    public String importMavenDependency(
            @P("Maven coordinates: 'groupId:artifactId:version' or 'groupId:artifactId' for latest. " +
               "Examples: 'com.google.guava:guava:32.1.2-jre', 'com.fasterxml.jackson.core:jackson-databind'")
            String coordinates) {

        // Parse coordinates
        var parts = coordinates.split(":");
        if (parts.length < 2 || parts.length > 3) {
            return "Invalid coordinates format. Expected 'groupId:artifactId' or 'groupId:artifactId:version'. " +
                   "Examples: 'com.google.guava:guava' or 'com.google.guava:guava:32.1.2-jre'";
        }

        var groupId = parts[0].trim();
        var artifactId = parts[1].trim();
        String version;

        if (parts.length == 3) {
            version = parts[2].trim();
        } else {
            // Resolve latest version from Maven Central
            logger.info("No version specified for {}:{}, resolving latest from Maven Central", groupId, artifactId);
            var fetcher = new MavenArtifactFetcher();
            var latestOpt = fetcher.resolveLatestVersion(groupId, artifactId);
            if (latestOpt.isEmpty()) {
                return "Could not resolve latest version for %s:%s from Maven Central. " +
                       "Try specifying an explicit version.".formatted(groupId, artifactId);
            }
            version = latestOpt.get();
            logger.info("Resolved latest version: {}", version);
        }

        var fullCoordinates = "%s:%s:%s".formatted(groupId, artifactId, version);

        // Download JAR
        logger.info("Fetching artifact: {}", fullCoordinates);
        var fetcher = new MavenArtifactFetcher();
        var jarPathOpt = fetcher.fetch(fullCoordinates, null);
        if (jarPathOpt.isEmpty()) {
            return "Could not find artifact %s on Maven Central. " +
                   "Check the coordinates and try again.".formatted(fullCoordinates);
        }

        var jarPath = jarPathOpt.get();
        var projectRoot = contextManager.getProject().getRoot();

        // Decompile/extract to .brokk/dependencies/
        logger.info("Importing JAR: {}", jarPath);
        var resultOpt = Decompiler.decompileJarBlocking(jarPath, projectRoot, false);
        if (resultOpt.isEmpty()) {
            return "Failed to import %s. Check logs for details.".formatted(fullCoordinates);
        }

        var result = resultOpt.get();
        var relativeOutput = projectRoot.relativize(result.outputDir());
        var sourceInfo = result.usedSources() ? " (from sources JAR)" : " (decompiled)";

        return "Successfully imported %s to %s (%d Java files%s). " +
               "The library is now available in Code Intelligence."
                       .formatted(fullCoordinates, relativeOutput, result.filesExtracted(), sourceInfo);
    }
}
