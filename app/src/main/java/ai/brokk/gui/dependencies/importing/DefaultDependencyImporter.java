package ai.brokk.gui.dependencies.importing;

import static java.util.Objects.requireNonNull;

import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.DependencyCopyUtil;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.NodeJsDependencyHelper;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.dependencies.DependenciesPanel;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.ICoreProject;
import ai.brokk.util.Decompiler;
import ai.brokk.util.FileUtil;
import ai.brokk.util.LocalCacheScanner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class DefaultDependencyImporter implements DependencyImporter {
    private static final Logger logger = LogManager.getLogger(DefaultDependencyImporter.class);

    @Override
    public Language.ImportSupport getImportSupport(Language language) {
        if (language.internalName().equalsIgnoreCase("JAVA")) return Language.ImportSupport.BASIC;
        if (language.internalName().equalsIgnoreCase("PYTHON")) return Language.ImportSupport.BASIC;
        if (language.internalName().equalsIgnoreCase("RUST")) return Language.ImportSupport.FINE_GRAINED;
        if (language.internalName().equalsIgnoreCase("TYPESCRIPT")) return Language.ImportSupport.FINE_GRAINED;
        if (language.internalName().equalsIgnoreCase("JAVASCRIPT")) return Language.ImportSupport.FINE_GRAINED;
        return Language.ImportSupport.NONE;
    }

    @Override
    public List<Language.DependencyCandidate> listDependencyPackages(ICoreProject project, Language language) {
        return switch (language.internalName().toUpperCase(Locale.ROOT)) {
            case "JAVA" -> listJavaJars();
            case "PYTHON", "RUST" -> language.listDependencyPackages(project);
            case "TYPESCRIPT", "JAVASCRIPT" -> NodeJsDependencyHelper.listDependencyPackages(project);
            default -> List.of();
        };
    }

    @Override
    public boolean importDependency(
            Chrome chrome,
            Language language,
            Language.DependencyCandidate pkg,
            @Nullable DependenciesPanel.DependencyLifecycleListener lifecycle) {
        return switch (language.internalName().toUpperCase(Locale.ROOT)) {
            case "JAVA" -> importJavaJar(chrome, pkg, lifecycle);
            case "PYTHON" -> importPythonPackage(chrome, pkg, lifecycle);
            case "RUST" -> importRustCrate(chrome, pkg, lifecycle);
            case "TYPESCRIPT", "JAVASCRIPT" -> importNodePackage(chrome, pkg, lifecycle);
            default -> false;
        };
    }

    @Override
    public boolean isAnalyzed(ICoreProject project, Language language, Path pathToImport) {
        return switch (language.internalName().toUpperCase(Locale.ROOT)) {
            case "PYTHON", "RUST" -> language.isAnalyzed(project, pathToImport);
            case "TYPESCRIPT", "JAVASCRIPT" -> NodeJsDependencyHelper.isAnalyzed(project, pathToImport);
            default -> language.isAnalyzed(project, pathToImport);
        };
    }

    // ---- Java ----

    private List<Language.DependencyCandidate> listJavaJars() {
        var jars = LocalCacheScanner.listAllJars();
        var byFilename = new LinkedHashMap<String, Path>();
        for (var p : jars) {
            var name = p.getFileName().toString();
            byFilename.putIfAbsent(name, p);
        }

        var pkgs = new ArrayList<Language.DependencyCandidate>();
        for (var p : byFilename.values()) {
            var display = prettyJarName(p);
            var count = countJarFiles(p);
            pkgs.add(new Language.DependencyCandidate(display, p, Language.DependencyKind.NORMAL, count));
        }
        pkgs.sort(Comparator.comparing(Language.DependencyCandidate::displayName));
        return pkgs;
    }

    private boolean importJavaJar(
            Chrome chrome,
            Language.DependencyCandidate pkg,
            @Nullable DependenciesPanel.DependencyLifecycleListener lifecycle) {
        var depName = pkg.displayName();
        if (lifecycle != null) {
            SwingUtilities.invokeLater(() -> lifecycle.dependencyImportStarted(depName));
        }
        Decompiler.decompileJar(
                chrome,
                pkg.sourcePath(),
                chrome.getContextManager()::submitBackgroundTask,
                () -> SwingUtilities.invokeLater(() -> {
                    if (lifecycle != null) lifecycle.dependencyImportFinished(depName);
                }));
        return true;
    }

    private String prettyJarName(Path jarPath) {
        var fileName = jarPath.getFileName().toString();
        int dot = fileName.toLowerCase(Locale.ROOT).lastIndexOf(".jar");
        return (dot > 0) ? fileName.substring(0, dot) : fileName;
    }

    private long countJarFiles(Path jarPath) {
        try (var zip = new ZipFile(jarPath.toFile())) {
            return zip.stream()
                    .filter(e -> !e.isDirectory())
                    .map(e -> e.getName().toLowerCase(Locale.ROOT))
                    .filter(name -> name.endsWith(".class") || name.endsWith(".java"))
                    .count();
        } catch (IOException e) {
            return 0L;
        }
    }

    // ---- Python ----

    private boolean importPythonPackage(
            Chrome chrome,
            Language.DependencyCandidate pkg,
            @Nullable DependenciesPanel.DependencyLifecycleListener lifecycle) {
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

        chrome.getContextManager().submitBackgroundTask("Copying Python package: " + pkg.displayName(), () -> {
            try {
                Files.createDirectories(requireNonNull(targetRoot.getParent()));
                if (Files.exists(targetRoot)) {
                    if (!FileUtil.deleteRecursively(targetRoot)) {
                        throw new IOException("Failed to delete existing destination: " + targetRoot);
                    }
                }

                var meta = DependencyCopyUtil.readPyMetadata(distInfoDir);
                var rels = DependencyCopyUtil.enumerateInstalledFiles(
                        requireNonNull(sitePackages), distInfoDir, meta != null ? meta.name() : pkg.displayName());
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
                SwingUtilities.invokeLater(
                        () -> chrome.toolError("Error copying Python package: " + ex.getMessage(), "Python Import"));
            }
            return null;
        });
        return true;
    }

    // ---- Rust ----

    private boolean importRustCrate(
            Chrome chrome,
            Language.DependencyCandidate pkg,
            @Nullable DependenciesPanel.DependencyLifecycleListener lifecycle) {
        var manifestPath = pkg.sourcePath();
        if (!Files.exists(manifestPath)) {
            SwingUtilities.invokeLater(() -> chrome.toolError(
                    "Could not locate crate sources in local Cargo cache for " + pkg.displayName()
                            + ".\nPlease run 'cargo build' in your project, then retry.",
                    "Rust Import"));
            return false;
        }
        var sourceRoot = requireNonNull(manifestPath.getParent());
        var targetRoot = chrome.getProject()
                .getRoot()
                .resolve(AbstractProject.BROKK_DIR)
                .resolve(AbstractProject.DEPENDENCIES_DIR)
                .resolve(pkg.displayName());

        final var currentListener = lifecycle;
        if (currentListener != null) {
            SwingUtilities.invokeLater(() -> currentListener.dependencyImportStarted(pkg.displayName()));
        }

        chrome.getContextManager().submitBackgroundTask("Copying Rust crate: " + pkg.displayName(), () -> {
            try {
                Files.createDirectories(requireNonNull(targetRoot.getParent()));
                if (Files.exists(targetRoot)) {
                    if (!FileUtil.deleteRecursively(targetRoot)) {
                        throw new IOException("Failed to delete existing destination: " + targetRoot);
                    }
                }
                DependencyCopyUtil.copyRustCrate(sourceRoot, targetRoot);
                SwingUtilities.invokeLater(() -> {
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            "Rust crate copied to " + targetRoot + ". Reopen project to incorporate the new files.");
                    if (currentListener != null) currentListener.dependencyImportFinished(pkg.displayName());
                });
            } catch (Exception ex) {
                logger.error(
                        "Error copying Rust crate {} from {} to {}", pkg.displayName(), sourceRoot, targetRoot, ex);
                SwingUtilities.invokeLater(
                        () -> chrome.toolError("Error copying Rust crate: " + ex.getMessage(), "Rust Import"));
            }
            return null;
        });
        return true;
    }

    // ---- Node (JS/TS) ----

    private boolean importNodePackage(
            Chrome chrome,
            Language.DependencyCandidate pkg,
            @Nullable DependenciesPanel.DependencyLifecycleListener lifecycle) {
        var sourceRoot = pkg.sourcePath();
        if (!Files.exists(sourceRoot)) {
            SwingUtilities.invokeLater(() -> chrome.toolError(
                    "Could not locate NPM package sources at " + sourceRoot
                            + ".\nPlease run 'npm install' or 'pnpm install' in your project, then retry.",
                    "NPM Import"));
            return false;
        }

        var meta = NodeJsDependencyHelper.readPackageJsonFromDir(sourceRoot);
        var folderName = (meta != null && !meta.name.isEmpty())
                ? DependencyCopyUtil.toSafeFolderName(meta.name, meta.version)
                : pkg.displayName().replace("/", "__");
        var targetRoot = chrome.getProject()
                .getRoot()
                .resolve(AbstractProject.BROKK_DIR)
                .resolve(AbstractProject.DEPENDENCIES_DIR)
                .resolve(folderName);

        final var currentListener = lifecycle;
        if (currentListener != null) {
            SwingUtilities.invokeLater(() -> currentListener.dependencyImportStarted(pkg.displayName()));
        }

        chrome.getContextManager().submitBackgroundTask("Copying NPM package: " + pkg.displayName(), () -> {
            try {
                Files.createDirectories(requireNonNull(targetRoot.getParent()));
                if (Files.exists(targetRoot)) {
                    if (!FileUtil.deleteRecursively(targetRoot)) {
                        throw new IOException("Failed to delete existing destination: " + targetRoot);
                    }
                }
                DependencyCopyUtil.copyNodePackage(sourceRoot, targetRoot);
                SwingUtilities.invokeLater(() -> {
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            "NPM package copied to " + targetRoot + ". Reopen project to incorporate the new files.");
                    if (currentListener != null) currentListener.dependencyImportFinished(pkg.displayName());
                });
            } catch (IOException ex) {
                logger.error(
                        "Error copying NPM package {} from {} to {}", pkg.displayName(), sourceRoot, targetRoot, ex);
                SwingUtilities.invokeLater(
                        () -> chrome.toolError("Error copying NPM package: " + ex.getMessage(), "NPM Import"));
            }
            return null;
        });
        return true;
    }
}
