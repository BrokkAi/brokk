package ai.brokk.analyzer;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * INTERNAL utility for copying dependency files across different languages.
 * This class is public only for reuse across DependencyTools and the GUI dependency importers.
 * It is not a stable public API.
 */
public final class DependencyCopyUtil {
    private static final Logger logger = LogManager.getLogger(DependencyCopyUtil.class);

    private DependencyCopyUtil() {}

    // ========== Python Helpers ==========

    public record PyMeta(String name, String version) {}

    public static final List<String> PY_DOC_PREFIXES = List.of("readme", "license", "copying");

    public static @Nullable PyMeta readPyMetadata(Path distInfoDir) throws IOException {
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

    public static boolean pyIsAllowedFile(String fileNameLower) {
        if (fileNameLower.endsWith(".py") || fileNameLower.endsWith(".pyi")) return true;
        for (var prefix : PY_DOC_PREFIXES) {
            if (fileNameLower.startsWith(prefix)) return true;
        }
        return false;
    }

    public static List<Path> enumerateInstalledFiles(Path sitePackages, Path distInfoDir, String distName)
            throws IOException {
        var record = distInfoDir.resolve("RECORD");
        if (Files.exists(record)) {
            var rels = new ArrayList<Path>();
            for (var line : Files.readAllLines(record, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                String pathStr = line.split(",", 2)[0];
                var rel = Paths.get(pathStr);
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
                var rel = Paths.get(line.trim());
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

    public static void copyPythonFiles(Path sitePackages, List<Path> rels, Path dest) throws IOException {
        Files.createDirectories(dest);
        for (var rel : rels) {
            var src = sitePackages.resolve(rel);
            if (!Files.exists(src) || Files.isDirectory(src)) continue;
            var dst = dest.resolve(rel);
            Files.createDirectories(requireNonNull(dst.getParent()));
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ========== Rust Helpers ==========

    public static void copyRustCrate(Path source, Path destination) throws IOException {
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

    // ========== Node Helpers ==========

    public static void copyNodePackage(Path source, Path destination) throws IOException {
        // do not skip build/dist, that's where you find usually the lib code
        var skipDirs = List.of("node_modules", ".pnpm", ".git", "coverage", "test", "tests", ".nyc_output");
        // pnpm is based on symlinks, we need to follow it to find deps
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

    public static String toSafeFolderName(String name, String version) {
        var base = version.isEmpty() ? name : name + "@" + version;
        return base.replace("/", "__");
    }
}
