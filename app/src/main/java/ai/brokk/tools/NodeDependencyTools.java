package ai.brokk.tools;

import static java.util.Objects.requireNonNull;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.NodeJsDependencyHelper;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.IProject;
import ai.brokk.util.FileUtil;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.IOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;

/**
 * Tools for importing npm packages from a project's node_modules.
 * Designed for use by ArchitectAgent during the exploration phase.
 */
public class NodeDependencyTools {
    private static final Logger logger = LogManager.getLogger(NodeDependencyTools.class);

    private final IContextManager contextManager;

    public NodeDependencyTools(IContextManager cm) {
        this.contextManager = cm;
    }

    /**
     * Returns true if this tool is supported for the given project.
     * npm package import is supported for TypeScript and JavaScript projects.
     */
    public static boolean isSupported(IProject project) {
        var langs = project.getAnalyzerLanguages();
        return langs.contains(Languages.TYPESCRIPT) || langs.contains(Languages.JAVASCRIPT);
    }

    @Blocking
    @Tool("Import an npm package from your project's node_modules into Code Intelligence. "
            + "The package will be copied and added to the analyzer. "
            + "Use this when you need to understand or reference external library code.")
    public String importNpmPackage(
            @P("Package name. Examples: 'lodash', 'express', '@types/node'")
                    String packageName)
            throws InterruptedException {

        logger.info("importNpmPackage called with: {}", packageName);
        var io = contextManager.getIo();

        checkInterrupted();

        packageName = packageName.trim();
        if (packageName.isEmpty()) {
            return "Invalid package name. Expected a package name like 'lodash', 'express', or '@types/node'.";
        }

        var project = contextManager.getProject();

        // List available packages from node_modules
        io.showNotification(IConsoleIO.NotificationRole.INFO,
                            "Scanning node_modules for " + packageName + "...");
        var packages = NodeJsDependencyHelper.listDependencyPackages(project);
        if (packages.isEmpty()) {
            return "No npm packages found. Ensure you have a node_modules directory. "
                    + "Run 'npm install' or 'pnpm install' to install dependencies.";
        }

        // Find matching package
        Language.DependencyCandidate matchedPkg = null;
        String normalizedName = packageName.toLowerCase(Locale.ROOT);
        for (var pkg : packages) {
            var display = pkg.displayName().toLowerCase(Locale.ROOT);
            // Display format is "name@version" e.g., "lodash@4.17.21" or "name" if no version
            String pkgName;
            int atIndex = display.lastIndexOf('@');
            // Handle scoped packages like @types/node@1.0.0
            if (atIndex > 0) {
                pkgName = display.substring(0, atIndex);
            } else {
                pkgName = display;
            }

            if (pkgName.equals(normalizedName)) {
                matchedPkg = pkg;
                break;
            }
        }

        if (matchedPkg == null) {
            return "Package '%s' not found in node_modules. Run 'npm install %s' to install it."
                    .formatted(packageName, packageName);
        }

        checkInterrupted();

        // Copy the package
        var sourceRoot = matchedPkg.sourcePath();
        if (!Files.exists(sourceRoot)) {
            return "Could not locate NPM package sources at " + sourceRoot
                    + ". Please run 'npm install' or 'pnpm install' in your project, then retry.";
        }

        var meta = NodeJsDependencyHelper.readPackageJsonFromDir(sourceRoot);
        var folderName = (meta != null && !meta.name.isEmpty())
                ? toSafeFolderName(meta.name, meta.version)
                : matchedPkg.displayName().replace("/", "__");

        var projectRoot = project.getMasterRootPathForConfig();
        var targetRoot = projectRoot
                .resolve(AbstractProject.BROKK_DIR)
                .resolve(AbstractProject.DEPENDENCIES_DIR)
                .resolve(folderName);

        io.showNotification(IConsoleIO.NotificationRole.INFO,
                            "Copying " + matchedPkg.displayName() + "...");
        logger.info("Copying npm package {} from {} to {}", matchedPkg.displayName(), sourceRoot, targetRoot);

        try {
            Files.createDirectories(requireNonNull(targetRoot.getParent()));
            if (Files.exists(targetRoot)) {
                if (!FileUtil.deleteRecursively(targetRoot)) {
                    throw new IOException("Failed to delete existing destination: " + targetRoot);
                }
            }
            copyNodePackage(sourceRoot, targetRoot);
        } catch (IOException e) {
            logger.error("Error copying npm package {} from {} to {}",
                         matchedPkg.displayName(), sourceRoot, targetRoot, e);
            return "Failed to import " + matchedPkg.displayName() + ": " + e.getMessage();
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
            intelligenceStatus = "The package has been added to live dependencies and Code Intelligence is updating.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            logger.error("Failed to add live dependency: {}", depName, e);
            contextManager.requestRebuild();
            intelligenceStatus = "A Code Intelligence rebuild was requested and will update shortly.";
        }

        var relativeOutput = projectRoot.relativize(targetRoot);
        return "Successfully imported %s to %s (%d source files). %s"
                .formatted(matchedPkg.displayName(), relativeOutput, countNodeFiles(targetRoot), intelligenceStatus);
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Import cancelled");
        }
    }

    private static String toSafeFolderName(String name, String version) {
        var base = version.isEmpty() ? name : name + "@" + version;
        return base.replace("/", "__");
    }

    private long countNodeFiles(Path dir) {
        try (var stream = Files.walk(dir)) {
            return stream.filter(p -> !Files.isDirectory(p))
                    .filter(p -> {
                        var name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".js")
                                || name.endsWith(".mjs")
                                || name.endsWith(".cjs")
                                || name.endsWith(".jsx")
                                || name.endsWith(".ts")
                                || name.endsWith(".tsx")
                                || name.endsWith(".d.ts");
                    })
                    .count();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void copyNodePackage(Path source, Path destination) throws IOException {
        var skipDirs = Set.of("node_modules", ".pnpm", ".git", "coverage", "test", "tests", ".nyc_output");
        try (var stream = Files.walk(source, FileVisitOption.FOLLOW_LINKS)) {
            stream.forEach(src -> {
                try {
                    var rel = source.relativize(src);
                    var relStr = rel.toString().replace('\\', '/');
                    if (!relStr.isEmpty()) {
                        for (var d : skipDirs) {
                            if (relStr.equals(d) || relStr.startsWith(d + "/")) {
                                return; // skip unwanted directories
                            }
                        }
                    }
                    var dst = destination.resolve(rel);
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else {
                        var name = src.getFileName().toString().toLowerCase(Locale.ROOT);
                        boolean isAllowed = name.equals("package.json")
                                || name.startsWith("readme")
                                || name.startsWith("license")
                                || name.startsWith("copying")
                                || name.endsWith(".js")
                                || name.endsWith(".mjs")
                                || name.endsWith(".cjs")
                                || name.endsWith(".jsx")
                                || name.endsWith(".ts")
                                || name.endsWith(".tsx")
                                || name.endsWith(".d.ts");
                        if (isAllowed) {
                            Files.createDirectories(requireNonNull(dst.getParent()));
                            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } catch (FileSystemLoopException e) {
                    logger.warn("Circular symlink detected at {}, skipping", src);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
