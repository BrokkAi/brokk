package ai.brokk.analyzer;

import ai.brokk.project.ICoreProject;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

public class RustLanguage implements Language {
    private final Set<String> extensions = Set.of("rs");

    RustLanguage() {}

    @Override
    public Set<String> getExtensions() {
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
    public IAnalyzer createAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
        return new RustAnalyzer(project, listener);
    }

    @Override
    public IAnalyzer loadAnalyzer(ICoreProject project, IAnalyzer.ProgressListener listener) {
        var storage = getStoragePath(project);
        return TreeSitterStateIO.load(storage)
                .map(state -> (IAnalyzer) RustAnalyzer.fromState(project, state, listener))
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
                    "\\b$ident(?:<.+?>)?\\s*\\{", // struct initialization with optional generics
                    "\\b$ident(?:<.+?>)?\\s*\\(", // tuple struct with optional generics
                    "\\bimpl\\s+[^{\\n]+\\s+for\\s+$ident(?:<.+?>)?", // trait impl with optional generics
                    "\\bimpl(?:<.+?>)?\\s+$ident(?:<.+?>)?", // inherent impl with optional generics
                    "\\b$ident::", // path/associated items
                    ":\\s*$ident(?:<.+?>)?", // type annotations with optional generics
                    "->\\s*$ident(?:<.+?>)?", // return types with optional generics
                    "<\\s*$ident\\s*>", // as generic type argument
                    "\\buse\\s+[^{\\n]*::$ident\\b" // import statements
                    );
        }
        return Language.super.getSearchPatterns(type);
    }

    @Override
    public List<Path> getDependencyCandidates(ICoreProject project) {
        var meta = getMergedMetadata(project);
        return meta.packages.stream()
                .map(p -> p.manifest_path == null ? null : Path.of(p.manifest_path))
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    @Override
    public ImportSupport getDependencyImportSupport() {
        return ImportSupport.FINE_GRAINED;
    }

    @Override
    public List<DependencyCandidate> listDependencyPackages(ICoreProject project) {
        var meta = getMergedMetadata(project);
        if (meta.packages.isEmpty()) {
            return List.of();
        }

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

    private CargoMetadata getMergedMetadata(ICoreProject project) {
        var rootManifest = project.getRoot().resolve("Cargo.toml");
        List<Path> manifests = findCargoManifests(project);

        @Nullable CargoMetadata rootMeta = null;
        Set<Path> rootCoveredManifests = new LinkedHashSet<>();
        if (Files.isRegularFile(rootManifest)) {
            try {
                rootMeta = runCargoMetadata(rootManifest);
                rootCoveredManifests.add(rootManifest.normalize());
                for (var pkg : rootMeta.packages) {
                    if (pkg.manifest_path == null || pkg.manifest_path.isEmpty()) continue;
                    rootCoveredManifests.add(Path.of(pkg.manifest_path).normalize());
                }
            } catch (Exception e) {
                logger.debug("cargo metadata failed at project root: {}", e.toString());
            }
        }

        if (manifests.isEmpty()) {
            return rootMeta != null ? rootMeta : new CargoMetadata();
        }

        if (rootMeta != null
                && rootCoveredManifests.containsAll(
                        manifests.stream().map(Path::normalize).toList())) {
            return rootMeta;
        }

        CargoMetadata merged = rootMeta != null ? rootMeta : new CargoMetadata();
        if (merged.packages == null) merged.packages = new ArrayList<>();
        if (merged.workspace_members == null) merged.workspace_members = new ArrayList<>();

        Set<String> packageIdentities = new LinkedHashSet<>();
        Set<String> memberIds = new LinkedHashSet<>();
        for (var pkg : merged.packages) {
            String identity = pkg.id;
            if (identity.isEmpty()) {
                identity = String.format("%s:%s:%s:%s", pkg.name, pkg.version, pkg.manifest_path, pkg.source);
            }
            packageIdentities.add(identity);
        }
        memberIds.addAll(merged.workspace_members);

        for (var manifest : manifests) {
            if (rootCoveredManifests.contains(manifest.normalize())) {
                continue;
            }

            CargoMetadata meta;
            try {
                meta = runCargoMetadata(manifest);
            } catch (Exception e) {
                logger.warn("Failed to run cargo metadata for " + manifest, e);
                continue;
            }

            for (var pkg : meta.packages) {
                String identity = pkg.id;
                if (identity.isEmpty()) {
                    identity = String.format("%s:%s:%s:%s", pkg.name, pkg.version, pkg.manifest_path, pkg.source);
                }
                if (packageIdentities.add(identity)) {
                    merged.packages.add(pkg);
                }
            }
            memberIds.addAll(meta.workspace_members);
        }

        merged.workspace_members.addAll(memberIds);
        merged.packages.sort(Comparator.comparing(p -> p.id.isEmpty() ? p.name + p.version : p.id));
        merged.workspace_members.sort(Comparator.naturalOrder());

        return merged;
    }

    private List<Path> findCargoManifests(ICoreProject project) {
        var repo = project.getRepo();
        Set<ProjectFile> files = project.hasGit() ? repo.getTrackedFiles() : project.getAllFiles();
        return files.stream()
                .filter(pf -> pf.getFileName().equalsIgnoreCase("Cargo.toml"))
                .map(ProjectFile::absPath)
                .filter(p -> isCandidateManifest(project.getRoot(), p))
                .distinct()
                .sorted()
                .toList();
    }

    private boolean isCandidateManifest(Path projectRoot, Path manifestPath) {
        Path normalized = manifestPath.normalize();
        if (!normalized.startsWith(projectRoot)) return false;
        var rel =
                projectRoot.relativize(normalized).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (rel.isEmpty()) return false;
        for (var prefix : List.of(".brokk/", "target/", "vendor/", ".git/")) {
            if (rel.startsWith(prefix)) return false;
        }
        return true;
    }

    private CargoMetadata runCargoMetadata(Path manifestPath) throws IOException, InterruptedException {
        Path workingDir = manifestPath.getParent();
        if (workingDir == null) workingDir = manifestPath;

        var pb = new ProcessBuilder(
                "cargo", "metadata", "--format-version", "1", "--manifest-path", manifestPath.toString());
        pb.directory(workingDir.toFile());
        var process = pb.start();

        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> {
            try (var reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                var sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append('\n');
                return sb.toString();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> {
            try (var reader =
                    new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                var sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append('\n');
                return sb.toString();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        int exit = process.waitFor();
        String stdout = stdoutFuture.join();
        String stderr = stderrFuture.join();

        if (exit != 0) {
            throw new IOException(String.format(
                    "cargo metadata failed for %s with exit code %d.\nSTDOUT:\n%s\nSTDERR:\n%s",
                    manifestPath, exit, stdout, stderr));
        }

        var mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try {
            return mapper.readValue(stdout, CargoMetadata.class);
        } catch (Exception e) {
            throw new IOException(
                    String.format(
                            "Failed to parse cargo metadata JSON for %s.\nSTDOUT:\n%s\nSTDERR:\n%s",
                            manifestPath, stdout, stderr),
                    e);
        }
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

    // TODO: Refine isAnalyzed for Rust (e.g. target directory, .cargo, vendor)
    @Override
    public boolean isAnalyzed(ICoreProject project, Path pathToImport) {
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
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CargoMetadata {
        public List<CargoPackage> packages = List.of();
        public List<String> workspace_members = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CargoPackage {
        public String id = "";
        public String name = "";
        public String version = "";
        public @Nullable String manifest_path;
        public @Nullable String source;
        public List<CargoDependency> dependencies = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CargoDependency {
        public String name = "";
        public @Nullable String kind;
    }
}
