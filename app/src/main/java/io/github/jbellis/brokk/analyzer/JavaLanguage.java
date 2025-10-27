package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.dependencies.DependenciesPanel;
import io.github.jbellis.brokk.util.Decompiler;
import io.github.jbellis.brokk.util.Environment;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;

public class JavaLanguage implements Language {
    private final List<String> extensions = List.of("java");

    JavaLanguage() {}

    @Override
    public List<String> getExtensions() {
        return extensions;
    }

    @Override
    public String name() {
        return "Java";
    }

    @Override
    public String internalName() {
        return "JAVA";
    }

    @Override
    public String toString() {
        return name();
    }

    @Override
    public IAnalyzer createAnalyzer(IProject project) {
        return new JavaAnalyzer(project);
    }

    @Override
    public IAnalyzer loadAnalyzer(IProject project) {
        // the LSP server component will handle loading in the cache
        return createAnalyzer(project);
    }

    @Override
    public ImportSupport getDependencyImportSupport() {
        return ImportSupport.BASIC;
    }

    @Override
    public List<Path> getDependencyCandidates(IProject project) {
        long startTime = System.currentTimeMillis();

        String userHome = System.getProperty("user.home");
        if (userHome == null) {
            logger.warn("Could not determine user home directory.");
            return List.of();
        }
        Path homePath = Path.of(userHome);

        List<Path> rootsToScan = new ArrayList<>();

        /* ---------- default locations that exist on all OSes ---------- */
        rootsToScan.add(homePath.resolve(".m2").resolve("repository"));
        rootsToScan.add(homePath.resolve(".gradle")
                .resolve("caches")
                .resolve("modules-2")
                .resolve("files-2.1"));
        rootsToScan.add(homePath.resolve(".ivy2").resolve("cache"));
        rootsToScan.add(
                homePath.resolve(".cache").resolve("coursier").resolve("v1").resolve("https"));
        rootsToScan.add(homePath.resolve(".sbt"));

        /* ---------- honour user-supplied overrides ---------- */
        Optional.ofNullable(System.getenv("MAVEN_REPO")).map(Path::of).ifPresent(rootsToScan::add);

        Optional.ofNullable(System.getProperty("maven.repo.local"))
                .map(Path::of)
                .ifPresent(rootsToScan::add);

        Optional.ofNullable(System.getenv("GRADLE_USER_HOME"))
                .map(Path::of)
                .map(p -> p.resolve("caches").resolve("modules-2").resolve("files-2.1"))
                .ifPresent(rootsToScan::add);

        /* ---------- Windows-specific cache roots ---------- */
        if (Environment.isWindows()) {
            Optional.ofNullable(System.getenv("LOCALAPPDATA")).ifPresent(localAppData -> {
                Path lad = Path.of(localAppData);
                rootsToScan.add(
                        lad.resolve("Coursier").resolve("cache").resolve("v1").resolve("https"));
                rootsToScan.add(lad.resolve("Gradle")
                        .resolve("caches")
                        .resolve("modules-2")
                        .resolve("files-2.1"));
            });
        }

        /* ---------- macOS-specific cache roots ---------- */
        if (Environment.isMacOs()) {
            // Coursier on macOS defaults to ~/Library/Caches/Coursier/v1/https
            rootsToScan.add(homePath.resolve("Library")
                    .resolve("Caches")
                    .resolve("Coursier")
                    .resolve("v1")
                    .resolve("https"));
        }

        /* ---------- de-duplicate & scan ---------- */
        List<Path> uniqueRoots = rootsToScan.stream().distinct().toList();

        var jarFiles = uniqueRoots.parallelStream()
                .filter(Files::isDirectory)
                .peek(root -> logger.debug("Scanning for JARs under: {}", root))
                .flatMap(root -> {
                    try (Stream<Path> s = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) {
                        return s.filter(Files::isRegularFile).toList().stream();
                    } catch (IOException e) {
                        logger.warn("Error walking directory {}: {}", root, e.getMessage());
                        return Stream.empty();
                    } catch (SecurityException e) {
                        logger.warn("Permission denied accessing directory {}: {}", root, e.getMessage());
                        return Stream.empty();
                    }
                })
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    return name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar");
                })
                .toList();

        long duration = System.currentTimeMillis() - startTime;
        logger.info("Found {} JAR files in common dependency locations in {} ms", jarFiles.size(), duration);

        return jarFiles;
    }

    @Override
    public List<DependencyCandidate> listDependencyPackages(IProject project) {
        var jars = getDependencyCandidates(project);
        // Dedup by filename (keep first), then pretty name + count class/java entries
        var byFilename = new LinkedHashMap<String, Path>();
        for (var p : jars) {
            var name = p.getFileName().toString();
            byFilename.putIfAbsent(name, p);
        }

        var pkgs = new ArrayList<DependencyCandidate>();
        for (var p : byFilename.values()) {
            var display = prettyJarName(p);
            var count = countJarFiles(p);
            pkgs.add(new DependencyCandidate(display, p, DependencyKind.NORMAL, count));
        }
        pkgs.sort(Comparator.comparing(DependencyCandidate::displayName));
        return pkgs;
    }

    @Override
    public boolean importDependency(
            Chrome chrome, DependencyCandidate pkg, @Nullable DependenciesPanel.DependencyLifecycleListener lifecycle) {
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

    // ---- helpers moved from ImportJavaPanel ----
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
}
