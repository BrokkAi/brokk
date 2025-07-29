package io.github.jbellis.brokk.cpg;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Very small, project-scoped cache that maps a hash of (filePath + mtime) to a
 * previously-built CPG.  Performs simple LRU eviction based
 * on {@link java.nio.file.attribute.FileTime} of the cached files.
 *
 * Enabled only when the environment variable {@code BRK_CPG_CACHE} is set.
 */
public final class CpgCache
{
    private static final Logger logger = LogManager.getLogger(CpgCache.class);
    private static final String ENV_FLAG = "BRK_CPG_CACHE";
    private static final int MAX_ENTRIES = 100;

    private CpgCache() {}

    public static IAnalyzer getOrCompute(IProject project,
                                         Language language,
                                         Supplier<IAnalyzer> builder)
    {
        // Fast path: cache disabled or language not CPG-based
        if (!language.isCpg() || System.getenv(ENV_FLAG) == null) {
            return builder.get();
        }

        try {
            Path cacheDir = project.getMasterRootPathForConfig()
                                   .resolve(".brokk")
                                   .resolve("cache");
            Files.createDirectories(cacheDir);

            String key       = computeHash(project, language);
            Path   cacheFile = cacheDir.resolve(key + ".cpg");
            Path   projCpg   = language.getCpgPath(project);

            if (Files.exists(cacheFile)) {
                logger.debug("CPG cache hit for {} ({})", language.name(), key);
                copy(cacheFile, projCpg);
                touch(cacheFile);              // update access time for LRU
                return language.loadAnalyzer(project);
            }

            logger.debug("CPG cache miss for {} ({}) – building", language.name(), key);
            IAnalyzer analyzer = builder.get();

            if (Files.exists(projCpg)) {
                copy(projCpg, cacheFile);
                evictOldest(cacheDir);
            } else {
                logger.warn("Expected CPG file {} not found after build, skipping cache store", projCpg);
            }
            return analyzer;
        } catch (Exception e) {
            logger.warn("Falling back to uncached build due to error in CpgCache", e);
            return builder.get();
        }
    }

    /* ─────────────────────────  Helpers  ───────────────────────── */

    /**
     * Compute a stable fingerprint for the project w.r.t. one language.
     * <p>
     * Each file’s contribution is <code>path + ':' + SHA-256(contents)</code>.
     * The list is built in parallel (content hashing) and then sorted to keep the
     * final digest deterministic.
     */
    private static String computeHash(IProject project, Language lang) throws IOException, NoSuchAlgorithmException {
        // Collect all relevant project files first (serial); order does not matter here.
        List<ProjectFile> files = project.getAllFiles()
                                         .stream()
                                         .filter(pf -> lang.getExtensions().contains(pf.extension()))
                                         .toList();

        // Hash individual files in parallel.
        List<String> perFileEntries = files.parallelStream()
                                           .map(pf -> {
                                               Path p = pf.absPath();
                                               if (!Files.exists(p)) return ""; // deleted; ignore
                                               try {
                                                   // Hash file contents
                                                   MessageDigest md = MessageDigest.getInstance("SHA-256");
                                                   byte[] content   = Files.readAllBytes(p);
                                                   md.update(content);
                                                   String contentHash = HexFormat.of().formatHex(md.digest());
                                                   return pf.toString() + ':' + contentHash + '\n';
                                               } catch (IOException e) {
                                                   logger.debug("Could not read {} while computing CPG cache key: {}", p, e.getMessage());
                                                   return "";
                                               } catch (NoSuchAlgorithmException e) {
                                                   // Should never happen; rethrow unchecked so the caller will fail fast.
                                                   throw new RuntimeException(e);
                                               }
                                           })
                                           .filter(s -> !s.isEmpty())
                                           .sorted()           // deterministic order
                                           .toList();

        // Combine into one digest
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String entry : perFileEntries) {
            digest.update(entry.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void copy(Path src, Path dst) throws IOException {
        if (Objects.equals(src, dst)) return;
        Files.createDirectories(dst.getParent());
        // Use REPLACE_EXISTING to overwrite any stale file
        // ATOMIC_MOVE reduces the chance of half-written files being observed
        Path tmp = dst.resolveSibling(dst.getFileName() + ".tmp");
        Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void touch(Path path) {
        try {
            Files.setLastModifiedTime(path,
                                      java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException e) {
            // Non-fatal: log at debug level and continue; cache entry is still usable.
            logger.debug("Failed to update last-modified time for cache file {}", path, e);
        }
    }

    private static void evictOldest(Path cacheDir) {
        try (Stream<Path> files = Files.list(cacheDir)
                                      .filter(p -> p.getFileName().toString().endsWith(".cpg"))
                                      .sorted(Comparator.comparing(CpgCache::lastModifiedTime))) {

            List<Path> all = files.toList();
            if (all.size() <= MAX_ENTRIES) return;

            all.subList(0, all.size() - MAX_ENTRIES).forEach(old -> {
                try {
                    Files.deleteIfExists(old);
                    logger.debug("Evicted cached CPG {}", old.getFileName());
                } catch (IOException e) {
                    logger.warn("Failed to delete old cache file {}", old, e);
                }
            });
        } catch (IOException e) {
            logger.warn("Could not enforce LRU policy in {}", cacheDir, e);
        }
    }

    private static long lastModifiedTime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }
}
