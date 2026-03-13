package ai.brokk.analyzer;

import static java.util.Objects.requireNonNull;

import ai.brokk.IConsoleIO;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.dependencies.DependenciesPanel;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.IProject;
import ai.brokk.util.FileUtil;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;

public class PythonLanguage implements Language {
    public static final Pattern PY_SITE_PKGS = Pattern.compile("^python\\d+\\.\\d+$");
    private final Set<String> extensions = Set.of("py");

    PythonLanguage() {}

    @Override
    public Set<String> getExtensions() {
        return extensions;
    }

    @Override
    public String name() {
        return "Python";
    }

    @Override
    public String internalName() {
        return "PYTHON";
    }

    @Override
    public String toString() {
        return name();
    }

    @Override
    public IAnalyzer createAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
        return new PythonAnalyzer(project, listener);
    }

    @Override
    public IAnalyzer loadAnalyzer(IProject project, IAnalyzer.ProgressListener listener) {
        var storage = getStoragePath(project);
        return TreeSitterStateIO.load(storage)
                .map(state -> {
                    var analyzer = PythonAnalyzer.fromState(project, state, listener);
                    return (IAnalyzer) analyzer;
                })
                .orElseGet(() -> createAnalyzer(project, listener));
    }

    @Override
    public Set<String> getSearchPatterns(CodeUnitType type) {
        if (type == CodeUnitType.FUNCTION) {
            return Set.of(
                    "\\b$ident\\s*\\(", // function calls
                    "\\.$ident\\s*\\(" // method calls
                    );
        } else if (type == CodeUnitType.CLASS) {
            return Set.of(
                    "\\b$ident\\s*\\(", // constructor calls
                    "\\bclass\\s+\\w+\\s*\\([^)]*$ident[^)]*\\):", // inheritance
                    "\\b$ident\\s*\\.", // static/class access
                    ":\\s*$ident\\b", // type hints
                    "->\\s*$ident\\b", // return type hints
                    "\\bfrom\\s+.*\\s+import\\s+.*$ident", // from imports
                    "\\bimport\\s+.*\\.$ident\\b" // import statements
                    );
        }
        return Language.super.getSearchPatterns(type);
    }

    @Override
    public ImportSupport getDependencyImportSupport() {
        return ImportSupport.BASIC;
    }

    private List<Path> findVirtualEnvs(@Nullable Path root) {
        if (root == null) return List.of();
        List<Path> envs = new ArrayList<>();
        for (String candidate : List.of(".venv", "venv", "env")) {
            Path p = root.resolve(candidate);
            if (Files.isDirectory(p)) {
                logger.debug("Found virtual env at: {}", p);
                envs.add(p);
            }
        }
        // also look one level down for monorepos with /backend/venv etc.
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path sub : ds) {
                if (!Files.isDirectory(sub)) continue; // Skip files, only look in subdirectories
                Path venv = sub.resolve(".venv");
                if (Files.isDirectory(venv)) {
                    logger.debug("Found virtual env at: {}", venv);
                    envs.add(venv);
                }
            }
        } catch (IOException e) {
            logger.warn("Error scanning for virtual envs: {}", e.getMessage());
        }
        return envs;
    }

    private Path findSitePackagesInLibDir(Path libDir) {
        if (!Files.isDirectory(libDir)) {
            return Path.of("");
        }
        try (DirectoryStream<Path> pyVers = Files.newDirectoryStream(libDir)) {
            for (Path py : pyVers) {
                if (Files.isDirectory(py)
                        && PY_SITE_PKGS.matcher(py.getFileName().toString()).matches()) {
                    Path site = py.resolve("site-packages");
                    if (Files.isDirectory(site)) {
                        return site;
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Error scanning Python lib directory {}: {}", libDir, e.getMessage());
        }
        return Path.of("");
    }

    private Path sitePackagesDir(Path venv) {
        // Try "lib" first
        Path libDir = venv.resolve("lib");
        Path sitePackages = findSitePackagesInLibDir(libDir);
        if (Files.isDirectory(sitePackages)) { // Check if a non-empty and valid path was returned
            logger.debug("Found site-packages in: {}", sitePackages);
            return sitePackages;
        }

        // If not found in "lib", try "lib64"
        Path lib64Dir = venv.resolve("lib64");
        sitePackages = findSitePackagesInLibDir(lib64Dir);
        if (Files.isDirectory(sitePackages)) { // Check again
            logger.debug("Found site-packages in: {}", sitePackages);
            return sitePackages;
        }

        logger.debug("No site-packages found in {} or {}", libDir, lib64Dir);
        return Path.of(""); // Return empty path if not found in either
    }

    @Override
    public List<Path> getDependencyCandidates(IProject project) {
        logger.debug("Scanning for Python virtual environments in project: {}", project.getRoot());
        List<Path> venvs = findVirtualEnvs(project.getRoot());
        if (venvs.isEmpty()) {
            logger.debug("No virtual environments found for Python dependency scan.");
            return List.of();
        }

        List<Path> sitePackagesDirs = venvs.stream()
                .map(this::sitePackagesDir)
                .filter(Files::isDirectory)
                .toList();

        logger.debug("Found {} Python site-packages directories.", sitePackagesDirs.size());
        return sitePackagesDirs;
    }

    @Override
    public List<DependencyCandidate> listDependencyPackages(IProject project) {
        var sitePackagesDirs = getDependencyCandidates(project); // already scans venvs
        var rows = new ArrayList<DependencyCandidate>();
        var seen = new LinkedHashSet<String>();

        for (var site : sitePackagesDirs) {
            try (var stream = Files.list(site)) {
                for (var p : stream.toList()) {
                    var name = p.getFileName().toString();
                    if (name.endsWith(".dist-info") || name.endsWith(".egg-info")) {
                        var meta = DependencyCopyUtil.readPyMetadata(p);
                        if (meta == null) continue;

                        // Recompute file list to count like panel does
                        var files = DependencyCopyUtil.enumerateInstalledFiles(site, p, meta.name());
                        String display = meta.name() + " " + meta.version();
                        var key = display.toLowerCase(Locale.ROOT);
                        if (!seen.add(key)) continue; // de-dup across venvs
                        rows.add(new DependencyCandidate(display, p, DependencyKind.NORMAL, files.size()));
                    }
                }
            } catch (IOException e) {
                logger.debug("Skipping site-packages {} due to error: {}", site, e.toString());
            }
        }
        rows.sort(Comparator.comparing(DependencyCandidate::displayName));
        return rows;
    }

    @Override
    public boolean importDependency(
            Chrome chrome, DependencyCandidate pkg, @Nullable DependenciesPanel.DependencyLifecycleListener lifecycle) {

        var distInfoDir = pkg.sourcePath();
        var sitePackages = distInfoDir.getParent();
        if (sitePackages == null || !Files.exists(sitePackages)) {
            SwingUtilities.invokeLater(() -> chrome.toolError(
                    "Could not locate site-packages for " + pkg.displayName()
                            + ". Ensure your virtual environment exists and is built.",
                    "Python Import"));
            return false;
        }

        var targetRoot = chrome.getProject()
                .getRoot()
                .resolve(AbstractProject.BROKK_DIR)
                .resolve(AbstractProject.DEPENDENCIES_DIR)
                .resolve(pkg.displayName());

        final var currentListener = lifecycle;
        if (currentListener != null) {
            SwingUtilities.invokeLater(() -> currentListener.dependencyImportStarted(pkg.displayName()));
        }

        chrome.getContextManager()
                .getAnalyzerTaskSubmitter()
                .submit("Copying Python package: " + pkg.displayName(), () -> {
                    try {
                        Files.createDirectories(targetRoot.getParent());
                        if (Files.exists(targetRoot)) {
                            if (!FileUtil.deleteRecursively(targetRoot)) {
                                throw new IOException("Failed to delete existing destination: " + targetRoot);
                            }
                        }

                        // Re-enumerate files at import time to be robust
                        var meta = DependencyCopyUtil.readPyMetadata(distInfoDir);
                        var rels = DependencyCopyUtil.enumerateInstalledFiles(
                                requireNonNull(sitePackages),
                                distInfoDir,
                                meta != null ? meta.name() : pkg.displayName());
                        DependencyCopyUtil.copyPythonFiles(requireNonNull(sitePackages), rels, targetRoot);

                        SwingUtilities.invokeLater(() -> {
                            chrome.showNotification(
                                    IConsoleIO.NotificationRole.INFO,
                                    "Python package copied to " + targetRoot
                                            + ". Reopen project to incorporate the new files.");
                            if (currentListener != null) currentListener.dependencyImportFinished(pkg.displayName());
                        });
                    } catch (IOException ex) {
                        logger.error(
                                "Error copying Python package {} from {} to {}",
                                pkg.displayName(),
                                sitePackages,
                                targetRoot,
                                ex);
                        SwingUtilities.invokeLater(() ->
                                chrome.toolError("Error copying Python package: " + ex.getMessage(), "Python Import"));
                    }
                });
        return true;
    }

    @Override
    public boolean isAnalyzed(IProject project, Path pathToImport) {
        assert pathToImport.isAbsolute() : "Path must be absolute for isAnalyzed check: " + pathToImport;
        Path projectRoot = project.getRoot();
        Path normalizedPathToImport = pathToImport.normalize();

        if (!normalizedPathToImport.startsWith(projectRoot)) {
            return false; // Not part of this project
        }

        // Check if the path is inside any known virtual environment locations.
        // findVirtualEnvs looks at projectRoot and one level down.
        List<Path> venvPaths =
                findVirtualEnvs(projectRoot).stream().map(Path::normalize).toList();
        for (Path venvPath : venvPaths) {
            if (normalizedPathToImport.startsWith(venvPath)) {
                // Paths inside virtual environments are dependencies, not primary analyzed sources.
                return false;
            }
        }
        return true; // It's under project root and not in a known venv.
    }
}
