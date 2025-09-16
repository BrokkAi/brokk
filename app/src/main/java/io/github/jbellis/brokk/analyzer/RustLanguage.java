package io.github.jbellis.brokk.analyzer;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.AbstractProject;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.dependencies.DependenciesPanel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public class RustLanguage implements Language {
    private final List<String> extensions = List.of("rs");

    RustLanguage() {}

    @Override
    public List<String> getExtensions() {
        return extensions;
    }

    @Override
    public String name() {
        return "Rust";
    }

    @Override
    public String internalName() {
        return "RUST";
    }

    @Override
    public String toString() {
        return name();
    }

    @Override
    public IAnalyzer createAnalyzer(IProject project) {
        return new RustAnalyzer(project, project.getExcludedDirectories());
    }

    @Override
    public IAnalyzer loadAnalyzer(IProject project) {
        return createAnalyzer(project);
    }

    @Override
    public boolean providesSummaries() {
        return true;
    }

    @Override
    public boolean providesSourceCode() {
        return true;
    }

    @Override
    public boolean providesInterproceduralAnalysis() {
        return false;
    }

    @Override
    public List<Path> getDependencyCandidates(IProject project) {
        // Use cargo metadata to detect any packages; return non-empty when Cargo workspace is present.
        try {
            var meta = runCargoMetadata(project.getRoot());
            // Any discovered package manifests are candidates; this drives whether a tab is shown.
            return meta.packages.stream()
                    .map(p -> p.manifest_path == null ? null : Path.of(p.manifest_path))
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            logger.debug("cargo metadata failed for candidates: {}", e.toString());
            return List.of();
        }
    }

    @Override
    public boolean supportesDependencyKinds() {
        return true;
    }

    @Override
    public List<DependencyCandidate> listDependencyPackages(IProject project) {
        try {
            var meta = runCargoMetadata(project.getRoot());
            var idToPkg = new LinkedHashMap<String, CargoPackage>();
            var nameToPkgs = new LinkedHashMap<String, List<CargoPackage>>();
            for (var p : meta.packages) {
                idToPkg.put(p.id, p);
                nameToPkgs.computeIfAbsent(p.name, k -> new ArrayList<>()).add(p);
            }
            var workspaceIds = new LinkedHashSet<>(meta.workspace_members);

            // direct deps kinds (normal/build/dev/test) by name
            var directKinds = new LinkedHashMap<String, Set<String>>();
            for (var wsId : workspaceIds) {
                var ws = idToPkg.get(wsId);
                if (ws == null) continue;
                for (var dep : ws.dependencies) {
                    var kind = dep.kind == null ? "normal" : dep.kind;
                    directKinds
                            .computeIfAbsent(dep.name, k -> new LinkedHashSet<>())
                            .add(kind);
                }
            }

            var chosenDirect = new LinkedHashMap<String, DependencyCandidate>();
            for (var e : directKinds.entrySet()) {
                var name = e.getKey();
                var kinds = e.getValue();
                var pkgs = nameToPkgs.getOrDefault(name, List.of()).stream()
                        .filter(p -> !workspaceIds.contains(p.id))
                        .toList();
                if (pkgs.isEmpty()) continue;

                var selected =
                        pkgs.stream().max(Comparator.comparing(p -> p.version)).orElse(pkgs.getFirst());

                if (selected.manifest_path == null) {
                    continue;
                }
                var manifest = Path.of(selected.manifest_path);
                long files = countRustFiles(manifest);
                var display = selected.name + " " + selected.version;
                var kind = pickRustKind(kinds);
                chosenDirect.put(name, new DependencyCandidate(display, manifest, kind, files));
            }

            var directNames = new LinkedHashSet<>(chosenDirect.keySet());
            var all = new ArrayList<DependencyCandidate>(chosenDirect.values());

            for (var p : meta.packages) {
                if (workspaceIds.contains(p.id)) continue;
                if (directNames.contains(p.name)) continue;
                if (p.manifest_path == null) continue;
                var manifest = Path.of(p.manifest_path);
                long files = countRustFiles(manifest);
                var display = p.name + " " + p.version;
                all.add(new DependencyCandidate(display, manifest, DependencyKind.TRANSITIVE, files));
            }

            all.sort(Comparator.comparing(DependencyCandidate::displayName));
            return all;
        } catch (Exception e) {
            logger.warn("Failed to list Rust dependencies via cargo metadata", e);
            return List.of();
        }
    }

    @Override
    public boolean importDependency(
            Chrome chrome, DependencyCandidate pkg, @Nullable DependenciesPanel.DependencyLifecycleListener lifecycle) {

        var manifestPath = pkg.sourcePath();
        if (!Files.exists(manifestPath)) {
            javax.swing.SwingUtilities.invokeLater(() -> chrome.toolError(
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
            javax.swing.SwingUtilities.invokeLater(() -> currentListener.dependencyImportStarted(pkg.displayName()));
        }

        chrome.getContextManager().submitBackgroundTask("Copying Rust crate: " + pkg.displayName(), () -> {
            try {
                Files.createDirectories(targetRoot.getParent());
                if (Files.exists(targetRoot)) {
                    if (!io.github.jbellis.brokk.util.FileUtil.deleteRecursively(targetRoot)) {
                        throw new IOException("Failed to delete existing destination: " + targetRoot);
                    }
                }
                copyRustCrate(sourceRoot, targetRoot);
                javax.swing.SwingUtilities.invokeLater(() -> {
                    chrome.systemOutput(
                            "Rust crate copied to " + targetRoot + ". Reopen project to incorporate the new files.");
                    if (currentListener != null) currentListener.dependencyImportFinished(pkg.displayName());
                });
            } catch (Exception ex) {
                logger.error(
                        "Error copying Rust crate {} from {} to {}", pkg.displayName(), sourceRoot, targetRoot, ex);
                javax.swing.SwingUtilities.invokeLater(
                        () -> chrome.toolError("Error copying Rust crate: " + ex.getMessage(), "Rust Import"));
            }
            return null;
        });
        return true;
    }

    private DependencyKind pickRustKind(Set<String> kinds) {
        // Prefer NORMAL, then BUILD, DEV, TEST.
        if (kinds.contains("normal")) return DependencyKind.NORMAL;
        if (kinds.contains("build")) return DependencyKind.BUILD;
        if (kinds.contains("dev")) return DependencyKind.DEV;
        if (kinds.contains("test")) return DependencyKind.TEST;
        return DependencyKind.UNKNOWN;
    }

    // ---- helpers (moved/adapted from ImportRustPanel) ----

    private CargoMetadata runCargoMetadata(Path projectRoot) throws IOException, InterruptedException {
        var pb = new ProcessBuilder("cargo", "metadata", "--format-version", "1");
        pb.directory(projectRoot.toFile());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        var process = pb.start();

        var stdout = new StringBuilder();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) stdout.append(line).append('\n');
        }
        int exit = process.waitFor();
        if (exit != 0) throw new IOException("cargo metadata failed with exit code " + exit);

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper.readValue(stdout.toString(), CargoMetadata.class);
    }

    private static long countRustFiles(@Nullable Path manifestPath) {
        if (manifestPath == null) return 0L;
        var root = manifestPath.getParent();
        if (root == null) return 0L;
        try (var stream = Files.walk(root)) {
            return stream.filter(p -> !Files.isDirectory(p))
                    .map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                    .filter(name -> name.endsWith(".rs")
                            || name.equals("cargo.toml")
                            || name.equals("cargo.lock")
                            || name.startsWith("readme")
                            || name.startsWith("license")
                            || name.startsWith("copying"))
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
                            Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    // TODO: Refine isAnalyzed for Rust (e.g. target directory, .cargo, vendor)
    @Override
    public boolean isAnalyzed(IProject project, Path pathToImport) {
        assert pathToImport.isAbsolute() : "Path must be absolute for isAnalyzed check: " + pathToImport;
        Path projectRoot = project.getRoot();
        Path normalizedPathToImport = pathToImport.normalize();

        if (!normalizedPathToImport.startsWith(projectRoot)) {
            return false; // Not part of this project
        }
        // Example: exclude target directory
        Path targetDir = projectRoot.resolve("target");
        if (normalizedPathToImport.startsWith(targetDir)) {
            return false;
        }
        // Example: exclude .cargo directory if it exists
        Path cargoDir = projectRoot.resolve(".cargo");
        return !Files.isDirectory(cargoDir)
                || !normalizedPathToImport.startsWith(
                        cargoDir); // Default: if under project root and not in typical build/dependency dirs
    }

    // --- cargo metadata DTOs for Rust importer ---
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class CargoMetadata {
        public List<CargoPackage> packages = List.of();
        public List<String> workspace_members = List.of();
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class CargoPackage {
        public String id = "";
        public String name = "";
        public String version = "";
        public @Nullable String manifest_path;
        public @Nullable String source;
        public List<CargoDependency> dependencies = List.of();
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class CargoDependency {
        public String name = "";
        public @Nullable String kind;
    }
}
