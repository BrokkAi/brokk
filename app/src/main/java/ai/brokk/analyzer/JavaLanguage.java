package ai.brokk.analyzer;

import ai.brokk.project.ICoreProject;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.dependencies.DependenciesPanel;
import ai.brokk.project.IProject;
import ai.brokk.util.Decompiler;
import ai.brokk.util.LocalCacheScanner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipFile;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;

public class JavaLanguage implements JvmLanguage, DependencyImportable {
    private final Set<String> extensions = Set.of("java");

    JavaLanguage() {}

    @Override
    public Set<String> getExtensions() {
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
    public IAnalyzer createAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
        return new JavaAnalyzer(project, listener);
    }

    @Override
    public IAnalyzer loadAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
        var storage = getStoragePath(project);
        return TreeSitterStateIO.load(storage)
                .map(state -> {
                    var analyzer = JavaAnalyzer.fromState(project, state, listener);
                    return (IAnalyzer) analyzer;
                })
                .orElseGet(() -> createAnalyzer(project, listener));
    }

    @Override
    public Set<String> getSearchPatterns(CodeUnitType type) {
        if (type == CodeUnitType.FUNCTION) {
            return Set.of(
                    "\\b$ident\\s*\\(", // method calls: foo(...)
                    "::\\s*$ident\\b" // method references: ::foo or this::foo
                    );
        } else if (type == CodeUnitType.CLASS) {
            return Set.of(
                    "\\bnew\\s+$ident(?:<.+?>)?\\s*\\(", // constructor calls with optional generics
                    "\\bextends\\s+$ident(?:<.+?>)?", // inheritance with optional generics
                    "\\bimplements\\s+$ident(?:<.+?>)?", // interface implementation with optional generics
                    "\\b$ident\\s*\\.", // static access
                    "\\b$ident(?:<.+?>)?\\s+\\w+\\s*[;=]", // variable declaration with optional generics
                    "\\b$ident(?:<.+?>)?\\s+\\w+\\s*\\)", // parameter with optional generics
                    "<\\s*$ident\\s*>", // as generic type argument
                    "\\(\\s*$ident(?:<.+?>)?\\s*\\)", // cast with optional generics
                    "\\bimport\\s+.*\\.$ident\\b" // import
                    );
        }
        return JvmLanguage.super.getSearchPatterns(type);
    }

    @Override
    public ImportSupport getDependencyImportSupport() {
        return ImportSupport.BASIC;
    }

    @Override
    public List<Path> getDependencyCandidates(ICoreProject project) {
        return LocalCacheScanner.listAllJars();
    }

    @Override
    public List<DependencyCandidate> listDependencyPackages(ICoreProject project) {
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
