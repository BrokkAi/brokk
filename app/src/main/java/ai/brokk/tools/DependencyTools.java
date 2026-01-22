package ai.brokk.tools;

import static java.util.Objects.requireNonNull;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.NodeJsDependencyHelper;
import ai.brokk.analyzer.PythonLanguage;
import ai.brokk.analyzer.RustLanguage;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.IProject;
import ai.brokk.util.Decompiler;
import ai.brokk.util.DownloadProgressListener;
import ai.brokk.util.FileUtil;
import ai.brokk.util.LocalCacheScanner;
import ai.brokk.util.MavenArtifactFetcher;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Unified tool for importing dependencies across all supported languages.
 * Automatically detects the project language and routes to the appropriate importer.
 * Designed for use by ArchitectAgent during the exploration phase.
 *
 * <p>This class delegates to the shared copy helpers in the Language classes
 * (PythonLanguage, RustLanguage, NodeJsDependencyHelper) to avoid code duplication
 * with the GUI import functionality.
 */
public class DependencyTools {
    private static final Logger logger = LogManager.getLogger(DependencyTools.class);

    private final IContextManager contextManager;
    private final @Nullable MavenArtifactFetcher fetcher;

    public DependencyTools(IContextManager cm) {
        this(cm, null);
    }

    public DependencyTools(IContextManager cm, @Nullable MavenArtifactFetcher fetcher) {
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
     * Returns true if dependency import is supported for the given project.
     * Supported languages: Java, Python, Rust, TypeScript, JavaScript.
     */
    public static boolean isSupported(IProject project) {
        var langs = project.getAnalyzerLanguages();
        return langs.contains(Languages.JAVA)
                || langs.contains(Languages.PYTHON)
                || langs.contains(Languages.RUST)
                || langs.contains(Languages.TYPESCRIPT)
                || langs.contains(Languages.JAVASCRIPT);
    }

    /**
     * Returns a description of supported languages for this project.
     */
    private String getSupportedLanguagesDescription() {
        var langs = contextManager.getProject().getAnalyzerLanguages();
        var supported = new ArrayList<String>();
        if (langs.contains(Languages.JAVA)) supported.add("Java (Maven coordinates)");
        if (langs.contains(Languages.PYTHON)) supported.add("Python (pip packages)");
        if (langs.contains(Languages.RUST)) supported.add("Rust (crates)");
        if (langs.contains(Languages.TYPESCRIPT) || langs.contains(Languages.JAVASCRIPT)) {
            supported.add("Node.js (npm packages)");
        }
        return String.join(", ", supported);
    }

    @Blocking
    @Tool("Import a dependency into Code Intelligence. Automatically detects the language based on the project. "
            + "For Java: use Maven coordinates like 'com.google.guava:guava:32.1.2-jre'. "
            + "For Python: use package name like 'requests' or 'numpy 2.0.0'. "
            + "For Rust: use crate name like 'serde' or 'tokio 1.0'. "
            + "For Node.js: use package name like 'lodash' or '@types/node'.")
    public String importDependency(
            @P("Dependency specification. Format depends on language: "
                    + "Java='groupId:artifactId:version', Python='package [version]', "
                    + "Rust='crate [version]', Node.js='package'")
                    String dependencySpec)
            throws InterruptedException {

        logger.info("importDependency called with: {}", dependencySpec);

        checkInterrupted();

        dependencySpec = dependencySpec.trim();
        if (dependencySpec.isEmpty()) {
            return "Invalid dependency specification. Supported: " + getSupportedLanguagesDescription();
        }

        var project = contextManager.getProject();
        var langs = project.getAnalyzerLanguages();

        // Route based on project language and spec format
        // Java detection: contains colon (Maven coordinates)
        if (langs.contains(Languages.JAVA) && dependencySpec.contains(":")) {
            return importMavenDependency(dependencySpec);
        }

        // For non-Maven specs, try languages in order of specificity
        if (langs.contains(Languages.PYTHON)) {
            return importPythonPackage(dependencySpec);
        }

        if (langs.contains(Languages.RUST)) {
            return importRustCrate(dependencySpec);
        }

        if (langs.contains(Languages.TYPESCRIPT) || langs.contains(Languages.JAVASCRIPT)) {
            return importNpmPackage(dependencySpec);
        }

        // Fallback for Java without colons (might be a partial name)
        if (langs.contains(Languages.JAVA)) {
            return "For Java dependencies, use Maven coordinates format: 'groupId:artifactId' or 'groupId:artifactId:version'. "
                    + "Example: 'com.google.guava:guava:32.1.2-jre'";
        }

        return "No supported dependency import language detected. Supported: " + getSupportedLanguagesDescription();
    }

    // ========== Java/Maven Import ==========

    private String importMavenDependency(String coordinates) throws InterruptedException {
        logger.info("importMavenDependency called with: {}", coordinates);
        var io = contextManager.getIo();

        checkInterrupted();

        // Count colons to validate format (only accept g:a or g:a:v)
        long colonCount = coordinates.chars().filter(c -> c == ':').count();
        if (colonCount < 1 || colonCount > 2) {
            logger.warn("Invalid coordinates format: {}", coordinates);
            return "Invalid Maven coordinates format. Expected 'groupId:artifactId' or 'groupId:artifactId:version'. "
                    + "Examples: 'com.google.guava:guava' or 'com.google.guava:guava:32.1.2-jre'";
        }

        // Parse coordinates using Maven's DefaultArtifact
        String coordsToParse = colonCount == 1 ? coordinates + ":LATEST" : coordinates;
        DefaultArtifact artifact;
        try {
            artifact = new DefaultArtifact(coordsToParse);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid coordinates format: {}", coordinates);
            return "Invalid Maven coordinates format. Expected 'groupId:artifactId' or 'groupId:artifactId:version'. "
                    + "Examples: 'com.google.guava:guava' or 'com.google.guava:guava:32.1.2-jre'";
        }

        var groupId = artifact.getGroupId();
        var artifactId = artifact.getArtifactId();
        String version = colonCount == 1 ? null : artifact.getVersion();

        try (var internalFetcher = fetcher != null ? null : new MavenArtifactFetcher(createProgressListener(io))) {
            var activeFetcher = fetcher != null ? fetcher : internalFetcher;
            assert activeFetcher != null;

            // Resolve latest version if not provided
            if (version == null || version.isEmpty()) {
                io.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "Resolving latest version for " + groupId + ":" + artifactId + "...");
                var localVersionOpt = LocalCacheScanner.findLatestVersion(groupId, artifactId);
                if (localVersionOpt.isPresent()) {
                    version = localVersionOpt.get();
                    logger.info("Using latest local version for {}:{} -> {}", groupId, artifactId, version);
                } else {
                    logger.info("Resolving latest version for {}:{} from Maven Central", groupId, artifactId);
                    var latestOpt = activeFetcher.resolveLatestVersion(groupId, artifactId);
                    if (latestOpt.isEmpty()) {
                        logger.warn("Could not resolve latest version for {}:{}", groupId, artifactId);
                        return ("Could not resolve latest version for %s:%s. Try specifying an explicit version.")
                                .formatted(groupId, artifactId);
                    }
                    version = latestOpt.get();
                    logger.info("Resolved latest version from Maven Central: {}", version);
                }
            }

            var fullCoordinates = "%s:%s:%s".formatted(groupId, artifactId, version);

            checkInterrupted();

            // Check local caches first
            Path jarPath;
            var localJarOpt = LocalCacheScanner.findArtifact(groupId, artifactId, version);
            if (localJarOpt.isPresent()) {
                jarPath = localJarOpt.get();
                io.showNotification(IConsoleIO.NotificationRole.INFO, "Found " + fullCoordinates + " in local cache");
                logger.info("Using cached artifact: {}", jarPath);
            } else {
                io.showNotification(IConsoleIO.NotificationRole.INFO, "Downloading " + fullCoordinates + "...");
                logger.info("Fetching artifact: {}", fullCoordinates);
                var jarPathOpt = activeFetcher.fetch(fullCoordinates, null);
                if (jarPathOpt.isEmpty()) {
                    logger.warn("Artifact not found on Maven Central: {}", fullCoordinates);
                    return "Could not find artifact %s on Maven Central. Check the coordinates and try again."
                            .formatted(fullCoordinates);
                }
                jarPath = jarPathOpt.get();
                logger.debug("JAR downloaded to: {}", jarPath);
            }

            var projectRoot = contextManager.getProject().getMasterRootPathForConfig();

            checkInterrupted();

            // Decompile/extract to .brokk/dependencies/
            io.showNotification(
                    IConsoleIO.NotificationRole.INFO,
                    "Importing " + artifactId + " (this may take a moment for large libraries)...");
            logger.info("Importing JAR to {}", projectRoot.resolve(".brokk/dependencies"));
            var resultOpt = Decompiler.decompileJarBlocking(jarPath, projectRoot, false, activeFetcher);

            if (resultOpt.isEmpty()) {
                logger.error("Decompile failed for {}", fullCoordinates);
                return "Failed to import %s. Check logs for details.".formatted(fullCoordinates);
            }

            var result = resultOpt.get();
            logger.info("Import complete: {} files extracted", result.filesExtracted());
            var relativeOutput = projectRoot.relativize(result.outputDir());
            var sourceInfo = result.usedSources() ? " (from sources JAR)" : " (decompiled)";

            checkInterrupted();

            String intelligenceStatus = registerLiveDependency(result.outputDir().getFileName().toString());

            return "Successfully imported %s to %s (%d Java files%s). %s"
                    .formatted(fullCoordinates, relativeOutput, result.filesExtracted(), sourceInfo, intelligenceStatus);
        }
    }

    // ========== Python Import ==========

    private String importPythonPackage(String packageSpec) throws InterruptedException {
        logger.info("importPythonPackage called with: {}", packageSpec);
        var io = contextManager.getIo();

        checkInterrupted();

        String[] parts = packageSpec.split("\\s+", 2);
        String packageName = parts[0].toLowerCase(Locale.ROOT);
        String requestedVersion = parts.length > 1 ? parts[1].trim() : null;

        var project = contextManager.getProject();

        io.showNotification(IConsoleIO.NotificationRole.INFO,
                            "Scanning virtual environment for " + packageName + "...");
        var packages = Languages.PYTHON.listDependencyPackages(project);
        if (packages.isEmpty()) {
            return "No Python packages found. Ensure you have a virtual environment (venv, .venv, or env) "
                    + "with packages installed.";
        }

        Language.DependencyCandidate matchedPkg = findPythonPackage(packages, packageName, requestedVersion);

        if (matchedPkg == null) {
            var suggestion = requestedVersion != null
                    ? "Package '%s' version '%s' not found in virtual environment."
                    : "Package '%s' not found in virtual environment.";
            return (requestedVersion != null
                    ? suggestion.formatted(packageName, requestedVersion)
                    : suggestion.formatted(packageName))
                    + " Run 'pip install " + packageName + "' to install it.";
        }

        checkInterrupted();

        var distInfoDir = matchedPkg.sourcePath();
        var sitePackages = distInfoDir.getParent();
        if (sitePackages == null || !Files.exists(sitePackages)) {
            return "Could not locate site-packages for " + matchedPkg.displayName()
                    + ". Ensure your virtual environment exists and is built.";
        }

        var projectRoot = project.getMasterRootPathForConfig();
        var targetRoot = projectRoot
                .resolve(AbstractProject.BROKK_DIR)
                .resolve(AbstractProject.DEPENDENCIES_DIR)
                .resolve(matchedPkg.displayName());

        io.showNotification(IConsoleIO.NotificationRole.INFO, "Copying " + matchedPkg.displayName() + "...");
        logger.info("Copying Python package {} from {} to {}", matchedPkg.displayName(), sitePackages, targetRoot);

        try {
            Files.createDirectories(targetRoot.getParent());
            if (Files.exists(targetRoot)) {
                if (!FileUtil.deleteRecursively(targetRoot)) {
                    throw new IOException("Failed to delete existing destination: " + targetRoot);
                }
            }

            // Use shared helpers from PythonLanguage
            var meta = PythonLanguage.readPyMetadata(distInfoDir);
            var rels = PythonLanguage.enumerateInstalledFiles(sitePackages, distInfoDir,
                                                              meta != null ? meta.name() : packageName);
            PythonLanguage.copyPythonFiles(sitePackages, rels, targetRoot);
        } catch (IOException e) {
            logger.error("Error copying Python package {} from {} to {}",
                         matchedPkg.displayName(), sitePackages, targetRoot, e);
            return "Failed to import " + matchedPkg.displayName() + ": " + e.getMessage();
        }

        checkInterrupted();

        String intelligenceStatus = registerLiveDependency(targetRoot.getFileName().toString());
        var relativeOutput = projectRoot.relativize(targetRoot);

        return "Successfully imported %s to %s (%d Python files). %s"
                .formatted(matchedPkg.displayName(), relativeOutput,
                           countFiles(targetRoot, ".py", ".pyi"), intelligenceStatus);
    }

    private @Nullable Language.DependencyCandidate findPythonPackage(
            List<Language.DependencyCandidate> packages, String packageName, @Nullable String requestedVersion) {
        for (var pkg : packages) {
            var display = pkg.displayName().toLowerCase(Locale.ROOT);
            String[] displayParts = display.split("\\s+", 2);
            String pkgName = displayParts[0];
            String pkgVersion = displayParts.length > 1 ? displayParts[1] : null;

            if (pkgName.equals(packageName) || pkgName.replace('-', '_').equals(packageName.replace('-', '_'))) {
                if (requestedVersion == null || requestedVersion.equals(pkgVersion)) {
                    return pkg;
                }
            }
        }
        return null;
    }

    // ========== Rust Import ==========

    private String importRustCrate(String crateSpec) throws InterruptedException {
        logger.info("importRustCrate called with: {}", crateSpec);
        var io = contextManager.getIo();

        checkInterrupted();

        String[] parts = crateSpec.split("\\s+", 2);
        String crateName = parts[0].toLowerCase(Locale.ROOT);
        String requestedVersion = parts.length > 1 ? parts[1].trim() : null;

        var project = contextManager.getProject();

        io.showNotification(IConsoleIO.NotificationRole.INFO,
                            "Scanning Cargo dependencies for " + crateName + "...");
        var crates = Languages.RUST.listDependencyPackages(project);
        if (crates.isEmpty()) {
            return "No Rust crates found. Ensure this is a Cargo project with dependencies. "
                    + "Run 'cargo build' to download dependencies.";
        }

        Language.DependencyCandidate matchedCrate = findRustCrate(crates, crateName, requestedVersion);

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

        io.showNotification(IConsoleIO.NotificationRole.INFO, "Copying " + matchedCrate.displayName() + "...");
        logger.info("Copying Rust crate {} from {} to {}", matchedCrate.displayName(), sourceRoot, targetRoot);

        try {
            Files.createDirectories(targetRoot.getParent());
            if (Files.exists(targetRoot)) {
                if (!FileUtil.deleteRecursively(targetRoot)) {
                    throw new IOException("Failed to delete existing destination: " + targetRoot);
                }
            }
            // Use shared helper from RustLanguage
            RustLanguage.copyRustCrate(sourceRoot, targetRoot);
        } catch (IOException e) {
            logger.error("Error copying Rust crate {} from {} to {}",
                         matchedCrate.displayName(), sourceRoot, targetRoot, e);
            return "Failed to import " + matchedCrate.displayName() + ": " + e.getMessage();
        }

        checkInterrupted();

        String intelligenceStatus = registerLiveDependency(targetRoot.getFileName().toString());
        var relativeOutput = projectRoot.relativize(targetRoot);

        return "Successfully imported %s to %s (%d Rust files). %s"
                .formatted(matchedCrate.displayName(), relativeOutput,
                           countFiles(targetRoot, ".rs"), intelligenceStatus);
    }

    private @Nullable Language.DependencyCandidate findRustCrate(
            List<Language.DependencyCandidate> crates, String crateName, @Nullable String requestedVersion) {
        for (var crate : crates) {
            var display = crate.displayName().toLowerCase(Locale.ROOT);
            String[] displayParts = display.split("\\s+", 2);
            String pkgName = displayParts[0];
            String pkgVersion = displayParts.length > 1 ? displayParts[1] : null;

            if (pkgName.equals(crateName)) {
                if (requestedVersion == null) {
                    return crate;
                } else if (pkgVersion != null && pkgVersion.startsWith(requestedVersion)) {
                    return crate;
                }
            }
        }
        return null;
    }

    // ========== Node.js Import ==========

    private String importNpmPackage(String packageName) throws InterruptedException {
        logger.info("importNpmPackage called with: {}", packageName);
        var io = contextManager.getIo();

        checkInterrupted();

        var project = contextManager.getProject();

        io.showNotification(IConsoleIO.NotificationRole.INFO,
                            "Scanning node_modules for " + packageName + "...");
        var packages = NodeJsDependencyHelper.listDependencyPackages(project);
        if (packages.isEmpty()) {
            return "No npm packages found. Ensure you have a node_modules directory. "
                    + "Run 'npm install' or 'pnpm install' to install dependencies.";
        }

        Language.DependencyCandidate matchedPkg = findNpmPackage(packages, packageName);

        if (matchedPkg == null) {
            return "Package '%s' not found in node_modules. Run 'npm install %s' to install it."
                    .formatted(packageName, packageName);
        }

        checkInterrupted();

        var sourceRoot = matchedPkg.sourcePath();
        if (!Files.exists(sourceRoot)) {
            return "Could not locate NPM package sources at " + sourceRoot
                    + ". Please run 'npm install' or 'pnpm install' in your project, then retry.";
        }

        var meta = NodeJsDependencyHelper.readPackageJsonFromDir(sourceRoot);
        var folderName = (meta != null && !meta.name.isEmpty())
                ? NodeJsDependencyHelper.toSafeFolderName(meta.name, meta.version)
                : matchedPkg.displayName().replace("/", "__");

        var projectRoot = project.getMasterRootPathForConfig();
        var targetRoot = projectRoot
                .resolve(AbstractProject.BROKK_DIR)
                .resolve(AbstractProject.DEPENDENCIES_DIR)
                .resolve(folderName);

        io.showNotification(IConsoleIO.NotificationRole.INFO, "Copying " + matchedPkg.displayName() + "...");
        logger.info("Copying npm package {} from {} to {}", matchedPkg.displayName(), sourceRoot, targetRoot);

        try {
            Files.createDirectories(requireNonNull(targetRoot.getParent()));
            if (Files.exists(targetRoot)) {
                if (!FileUtil.deleteRecursively(targetRoot)) {
                    throw new IOException("Failed to delete existing destination: " + targetRoot);
                }
            }
            // Use shared helper from NodeJsDependencyHelper
            NodeJsDependencyHelper.copyNodePackage(sourceRoot, targetRoot);
        } catch (IOException e) {
            logger.error("Error copying npm package {} from {} to {}",
                         matchedPkg.displayName(), sourceRoot, targetRoot, e);
            return "Failed to import " + matchedPkg.displayName() + ": " + e.getMessage();
        }

        checkInterrupted();

        String intelligenceStatus = registerLiveDependency(targetRoot.getFileName().toString());
        var relativeOutput = projectRoot.relativize(targetRoot);

        return "Successfully imported %s to %s (%d source files). %s"
                .formatted(matchedPkg.displayName(), relativeOutput,
                           countFiles(targetRoot, ".js", ".mjs", ".cjs", ".jsx", ".ts", ".tsx", ".d.ts"),
                           intelligenceStatus);
    }

    private @Nullable Language.DependencyCandidate findNpmPackage(
            List<Language.DependencyCandidate> packages, String packageName) {
        String normalizedName = packageName.toLowerCase(Locale.ROOT);
        for (var pkg : packages) {
            var display = pkg.displayName().toLowerCase(Locale.ROOT);
            String pkgName;
            int atIndex = display.lastIndexOf('@');
            if (atIndex > 0) {
                pkgName = display.substring(0, atIndex);
            } else {
                pkgName = display;
            }
            if (pkgName.equals(normalizedName)) {
                return pkg;
            }
        }
        return null;
    }

    // ========== Common Helpers ==========

    private String registerLiveDependency(String depName) {
        var io = contextManager.getIo();
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
            return "The dependency has been added to live dependencies and Code Intelligence is updating.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Import was interrupted during Code Intelligence registration.";
        } catch (Exception e) {
            logger.error("Failed to add live dependency: {}", depName, e);
            contextManager.requestRebuild();
            return "A Code Intelligence rebuild was requested and will update shortly.";
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Import cancelled");
        }
    }

    private long countFiles(Path dir, String... extensions) {
        try (var stream = Files.walk(dir)) {
            return stream.filter(p -> !Files.isDirectory(p))
                    .filter(p -> {
                        var name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        for (var ext : extensions) {
                            if (name.endsWith(ext)) return true;
                        }
                        return false;
                    })
                    .count();
        } catch (IOException e) {
            return 0L;
        }
    }

}
