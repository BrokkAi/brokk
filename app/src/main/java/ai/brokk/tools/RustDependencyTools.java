package ai.brokk.tools;

import static java.util.Objects.requireNonNull;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.IProject;
import ai.brokk.util.FileUtil;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;

/**
 * Tools for importing Rust crates from a project's Cargo cache.
 * Designed for use by ArchitectAgent during the exploration phase.
 */
public class RustDependencyTools {
    private static final Logger logger = LogManager.getLogger(RustDependencyTools.class);

    private final IContextManager contextManager;

    public RustDependencyTools(IContextManager cm) {
        this.contextManager = cm;
    }

    /**
     * Returns true if this tool is supported for the given project.
     * Rust crate import is only supported for Rust projects.
     */
    public static boolean isSupported(IProject project) {
        return project.getAnalyzerLanguages().contains(Languages.RUST);
    }

    @Blocking
    @Tool("Import a Rust crate from your project's dependencies into Code Intelligence. "
            + "The crate will be copied from Cargo cache and added to the analyzer. "
            + "Use this when you need to understand or reference external library code.")
    public String importRustCrate(
            @P("Crate name, optionally with version. Examples: 'serde', 'tokio 1.0'")
                    String crateSpec)
            throws InterruptedException {

        logger.info("importRustCrate called with: {}", crateSpec);
        var io = contextManager.getIo();

        checkInterrupted();

        crateSpec = crateSpec.trim();
        if (crateSpec.isEmpty()) {
            return "Invalid crate specification. Expected 'crate_name' or 'crate_name version'. "
                    + "Examples: 'serde', 'tokio 1.0'";
        }

        // Parse crate name and optional version
        String[] parts = crateSpec.split("\\s+", 2);
        String crateName = parts[0].toLowerCase(Locale.ROOT);
        String requestedVersion = parts.length > 1 ? parts[1].trim() : null;

        var project = contextManager.getProject();
        var rustLang = Languages.RUST;

        // List available crates via cargo metadata
        io.showNotification(IConsoleIO.NotificationRole.INFO,
                            "Scanning Cargo dependencies for " + crateName + "...");
        var crates = rustLang.listDependencyPackages(project);
        if (crates.isEmpty()) {
            return "No Rust crates found. Ensure this is a Cargo project with dependencies. "
                    + "Run 'cargo build' to download dependencies.";
        }

        // Find matching crate
        Language.DependencyCandidate matchedCrate = null;
        for (var crate : crates) {
            var display = crate.displayName().toLowerCase(Locale.ROOT);
            // Display format is "name version" e.g., "serde 1.0.197"
            String[] displayParts = display.split("\\s+", 2);
            String pkgName = displayParts[0];
            String pkgVersion = displayParts.length > 1 ? displayParts[1] : null;

            if (pkgName.equals(crateName)) {
                if (requestedVersion == null) {
                    // No version specified, take first match (highest version due to sorting)
                    matchedCrate = crate;
                    break;
                } else if (pkgVersion != null && pkgVersion.startsWith(requestedVersion)) {
                    // Allow partial version match (e.g., "1.0" matches "1.0.197")
                    matchedCrate = crate;
                    break;
                }
            }
        }

        if (matchedCrate == null) {
            var suggestion = requestedVersion != null
                    ? "Crate '%s' version '%s' not found in Cargo dependencies."
                    : "Crate '%s' not found in Cargo dependencies.";
            return (requestedVersion != null
                    ? suggestion.formatted(crateName, requestedVersion)
                    : suggestion.formatted(crateName))
                    + " Add it to Cargo.toml and run 'cargo build'.";
        }

        checkInterrupted();

        // Copy the crate
        var manifestPath = matchedCrate.sourcePath();
        if (!Files.exists(manifestPath)) {
            return "Could not locate crate sources in local Cargo cache for " + matchedCrate.displayName()
                    + ". Please run 'cargo build' in your project, then retry.";
        }
        var sourceRoot = requireNonNull(manifestPath.getParent());

        var projectRoot = project.getMasterRootPathForConfig();
        var targetRoot = projectRoot
                .resolve(AbstractProject.BROKK_DIR)
                .resolve(AbstractProject.DEPENDENCIES_DIR)
                .resolve(matchedCrate.displayName());

        io.showNotification(IConsoleIO.NotificationRole.INFO,
                            "Copying " + matchedCrate.displayName() + "...");
        logger.info("Copying Rust crate {} from {} to {}", matchedCrate.displayName(), sourceRoot, targetRoot);

        try {
            Files.createDirectories(targetRoot.getParent());
            if (Files.exists(targetRoot)) {
                if (!FileUtil.deleteRecursively(targetRoot)) {
                    throw new IOException("Failed to delete existing destination: " + targetRoot);
                }
            }
            copyRustCrate(sourceRoot, targetRoot);
        } catch (IOException e) {
            logger.error("Error copying Rust crate {} from {} to {}",
                         matchedCrate.displayName(), sourceRoot, targetRoot, e);
            return "Failed to import " + matchedCrate.displayName() + ": " + e.getMessage();
        }

        checkInterrupted();

        // Register with analyzer
        String depName = targetRoot.getFileName().toString();
        String intelligenceStatus;
        try {
            io.showNotification(IConsoleIO.NotificationRole.INFO, "Adding " + depName + " to Code Intelligence...");
            logger.debug("Adding {} to live dependencies...", depName);
            var analyzerWrapper = contextManager.getAnalyzerWrapper();
            contextManager
                    .getProject()
                    .addLiveDependency(depName, analyzerWrapper)
                    .get(60, TimeUnit.SECONDS);
            logger.info("Successfully added {} to live dependencies", depName);
            contextManager.notifyLiveDependenciesChanged();
            intelligenceStatus = "The crate has been added to live dependencies and Code Intelligence is updating.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            logger.error("Failed to add live dependency: {}", depName, e);
            contextManager.requestRebuild();
            intelligenceStatus = "A Code Intelligence rebuild was requested and will update shortly.";
        }

        var relativeOutput = projectRoot.relativize(targetRoot);
        return "Successfully imported %s to %s (%d Rust files). %s"
                .formatted(matchedCrate.displayName(), relativeOutput, countRustFiles(targetRoot), intelligenceStatus);
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Import cancelled");
        }
    }

    private long countRustFiles(Path dir) {
        try (var stream = Files.walk(dir)) {
            return stream.filter(p -> !Files.isDirectory(p))
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".rs"))
                    .count();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void copyRustCrate(Path source, Path destination) throws IOException {
        try (var stream = Files.walk(source)) {
            stream.forEach(src -> {
                try {
                    var rel = source.relativize(src);
                    if (rel.toString().startsWith("target")) return; // skip build artifacts
                    var dst = destination.resolve(rel);
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else {
                        var name = src.getFileName().toString().toLowerCase(Locale.ROOT);
                        boolean isRs = name.endsWith(".rs");
                        boolean isManifest = name.equals("cargo.toml") || name.equals("cargo.lock");
                        boolean isDoc =
                                name.startsWith("readme") || name.startsWith("license") || name.startsWith("copying");
                        if (isRs || isManifest || isDoc) {
                            Files.createDirectories(requireNonNull(dst.getParent()));
                            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
