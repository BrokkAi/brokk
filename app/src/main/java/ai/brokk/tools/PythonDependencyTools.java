package ai.brokk.tools;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.PythonLanguage;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.IProject;
import ai.brokk.util.FileUtil;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Tools for importing Python packages from a project's virtual environment.
 * Designed for use by ArchitectAgent during the exploration phase.
 */
public class PythonDependencyTools {
    private static final Logger logger = LogManager.getLogger(PythonDependencyTools.class);
    private static final List<String> PY_DOC_PREFIXES = List.of("readme", "license", "copying");

    private final IContextManager contextManager;

    public PythonDependencyTools(IContextManager cm) {
        this.contextManager = cm;
    }

    /**
     * Returns true if this tool is supported for the given project.
     * Python package import is only supported for Python projects.
     */
    public static boolean isSupported(IProject project) {
        return project.getAnalyzerLanguages().contains(Languages.PYTHON);
    }

    @Blocking
    @Tool("Import a Python package from your virtual environment into Code Intelligence. "
            + "The package will be copied from site-packages and added to the analyzer. "
            + "Use this when you need to understand or reference external library code.")
    public String importPythonPackage(
            @P("Package name, optionally with version. Examples: 'requests', 'numpy 2.0.0'")
                    String packageSpec)
            throws InterruptedException {

        logger.info("importPythonPackage called with: {}", packageSpec);
        var io = contextManager.getIo();

        checkInterrupted();

        packageSpec = packageSpec.trim();
        if (packageSpec.isEmpty()) {
            return "Invalid package specification. Expected 'package_name' or 'package_name version'. "
                    + "Examples: 'requests', 'numpy 2.0.0'";
        }

        // Parse package name and optional version
        String[] parts = packageSpec.split("\\s+", 2);
        String packageName = parts[0].toLowerCase(Locale.ROOT);
        String requestedVersion = parts.length > 1 ? parts[1].trim() : null;

        var project = contextManager.getProject();
        var pythonLang = Languages.PYTHON;

        // List available packages
        io.showNotification(IConsoleIO.NotificationRole.INFO,
                            "Scanning virtual environment for " + packageName + "...");
        var packages = pythonLang.listDependencyPackages(project);
        if (packages.isEmpty()) {
            return "No Python packages found. Ensure you have a virtual environment (venv, .venv, or env) "
                    + "with packages installed.";
        }

        // Find matching package
        Language.DependencyCandidate matchedPkg = null;
        for (var pkg : packages) {
            var display = pkg.displayName().toLowerCase(Locale.ROOT);
            // Display format is "name version" e.g., "requests 2.31.0"
            String[] displayParts = display.split("\\s+", 2);
            String pkgName = displayParts[0];
            String pkgVersion = displayParts.length > 1 ? displayParts[1] : null;

            if (pkgName.equals(packageName) || pkgName.replace('-', '_').equals(packageName.replace('-', '_'))) {
                if (requestedVersion == null) {
                    // No version specified, take first match
                    matchedPkg = pkg;
                    break;
                } else if (requestedVersion.equals(pkgVersion)) {
                    matchedPkg = pkg;
                    break;
                }
            }
        }

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

        // Copy the package
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

        io.showNotification(IConsoleIO.NotificationRole.INFO,
                            "Copying " + matchedPkg.displayName() + "...");
        logger.info("Copying Python package {} from {} to {}", matchedPkg.displayName(), sitePackages, targetRoot);

        try {
            Files.createDirectories(targetRoot.getParent());
            if (Files.exists(targetRoot)) {
                if (!FileUtil.deleteRecursively(targetRoot)) {
                    throw new IOException("Failed to delete existing destination: " + targetRoot);
                }
            }

            var meta = readPyMetadata(distInfoDir);
            var rels = enumerateInstalledFiles(sitePackages, distInfoDir, meta != null ? meta.name() : packageName);
            copyPythonFiles(sitePackages, rels, targetRoot);
        } catch (IOException e) {
            logger.error("Error copying Python package {} from {} to {}",
                         matchedPkg.displayName(), sitePackages, targetRoot, e);
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
        return "Successfully imported %s to %s (%d Python files). %s"
                .formatted(matchedPkg.displayName(), relativeOutput, countPythonFiles(targetRoot), intelligenceStatus);
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Import cancelled");
        }
    }

    private long countPythonFiles(Path dir) {
        try (var stream = Files.walk(dir)) {
            return stream.filter(p -> !Files.isDirectory(p))
                    .filter(p -> {
                        var name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".py") || name.endsWith(".pyi");
                    })
                    .count();
        } catch (IOException e) {
            return 0L;
        }
    }

    // Helpers adapted from PythonLanguage

    private record PyMeta(String name, String version) {}

    private @Nullable PyMeta readPyMetadata(Path distInfoDir) throws IOException {
        var meta = Files.exists(distInfoDir.resolve("METADATA"))
                ? distInfoDir.resolve("METADATA")
                : distInfoDir.resolve("PKG-INFO");
        if (!Files.exists(meta)) return null;

        String name = "";
        String version = "";
        try (var reader = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.regionMatches(true, 0, "Name:", 0, 5)) {
                    name = line.substring(5).trim();
                } else if (line.regionMatches(true, 0, "Version:", 0, 8)) {
                    version = line.substring(8).trim();
                }
                if (!name.isEmpty() && !version.isEmpty()) break;
            }
        }
        if (name.isEmpty() || version.isEmpty()) return null;
        return new PyMeta(name, version);
    }

    private static boolean pyIsAllowedFile(String fileNameLower) {
        if (fileNameLower.endsWith(".py") || fileNameLower.endsWith(".pyi")) return true;
        for (var prefix : PY_DOC_PREFIXES) {
            if (fileNameLower.startsWith(prefix)) return true;
        }
        return false;
    }

    private List<Path> enumerateInstalledFiles(Path sitePackages, Path distInfoDir, String distName)
            throws IOException {
        var record = distInfoDir.resolve("RECORD");
        if (Files.exists(record)) {
            var rels = new ArrayList<Path>();
            for (var line : Files.readAllLines(record, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                String pathStr = line.split(",", 2)[0];
                var rel = Path.of(pathStr);
                var abs = rel.isAbsolute() ? rel : sitePackages.resolve(rel).normalize();
                if (!abs.startsWith(sitePackages)) continue;
                if (Files.isDirectory(abs)) continue;
                var lower = abs.getFileName().toString().toLowerCase(Locale.ROOT);
                if (pyIsAllowedFile(lower)) rels.add(sitePackages.relativize(abs));
            }
            if (!rels.isEmpty()) return rels;
        }

        var installedFiles = distInfoDir.resolve("installed-files.txt");
        if (Files.exists(installedFiles)) {
            var rels = new ArrayList<Path>();
            for (var line : Files.readAllLines(installedFiles, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                var rel = Path.of(line.trim());
                var abs = rel.isAbsolute() ? rel : sitePackages.resolve(rel).normalize();
                if (!abs.startsWith(sitePackages)) continue;
                if (Files.isDirectory(abs)) continue;
                var lower = abs.getFileName().toString().toLowerCase(Locale.ROOT);
                if (pyIsAllowedFile(lower)) rels.add(sitePackages.relativize(abs));
            }
            if (!rels.isEmpty()) return rels;
        }

        // Fallback heuristic
        var normalized = distName.toLowerCase(Locale.ROOT).replace('-', '_');
        var rels = new ArrayList<Path>();
        var dirCandidate = sitePackages.resolve(normalized);
        var fileCandidate = sitePackages.resolve(normalized + ".py");
        if (Files.isDirectory(dirCandidate)) {
            try (var walk = Files.walk(dirCandidate)) {
                for (var abs : walk.filter(p -> !Files.isDirectory(p)).toList()) {
                    var lower = abs.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (pyIsAllowedFile(lower)) rels.add(sitePackages.relativize(abs));
                }
            }
        } else if (Files.exists(fileCandidate)) {
            rels.add(sitePackages.relativize(fileCandidate));
        }

        var meta = distInfoDir.resolve("METADATA");
        if (Files.exists(meta)) rels.add(sitePackages.relativize(meta));
        try (var s = Files.list(distInfoDir)) {
            for (var f : s.toList()) {
                var lower = f.getFileName().toString().toLowerCase(Locale.ROOT);
                for (var prefix : PY_DOC_PREFIXES) {
                    if (lower.startsWith(prefix)) {
                        rels.add(sitePackages.relativize(f));
                        break;
                    }
                }
            }
        }
        return rels;
    }

    private void copyPythonFiles(Path sitePackages, List<Path> rels, Path dest) throws IOException {
        Files.createDirectories(dest);
        for (var rel : rels) {
            var src = sitePackages.resolve(rel);
            if (!Files.exists(src) || Files.isDirectory(src)) continue;
            var dst = dest.resolve(rel);
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
