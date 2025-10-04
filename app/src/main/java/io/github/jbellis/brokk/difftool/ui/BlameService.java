package io.github.jbellis.brokk.difftool.ui;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Small async blame fetcher. Uses 'git blame --line-porcelain' when available.
 * Results are cached per-path. Failures return an empty map.
 *
 * Note: this is intentionally minimal and conservative — it runs the git command
 * off the EDT and returns a CompletableFuture that completes on the thread that
 * finishes the work; callers must SwingUtilities.invokeLater when updating UI.
 */
public final class BlameService {
    private static final Logger logger = LogManager.getLogger(BlameService.class);

    public static final record BlameInfo(String author, String shortSha, Long authorTime) {}

    // Simple cache keyed by absolute path (string)
    private final ConcurrentMap<Path, CompletableFuture<Map<Integer, BlameInfo>>> cache = new ConcurrentHashMap<>();

    /**
     * Feature flag check. Default: enabled. Can be controlled using system property
     * 'brokk.feature.blame'. To disable globally set -Dbrokk.feature.blame=false.
     */
    public static boolean isFeatureEnabled() {
        String v = System.getProperty("brokk.feature.blame", "true");
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    /**
     * Request blame for the given absolute file path. The returned future never completes exceptionally:
     * on error it completes with an empty map.
     */
    public CompletableFuture<Map<Integer, BlameInfo>> requestBlame(Path filePath) {
        if (!isFeatureEnabled()) {
            logger.debug("Blame feature disabled via system property");
            return CompletableFuture.completedFuture(Map.of());
        }
        logger.debug("Requesting blame for: {}", filePath);
        // filePath is treated as non-null by callers and static analysis; let computeIfAbsent handle invalid keys.
        return cache.computeIfAbsent(filePath, this::startBlameTask);
    }

    private CompletableFuture<Map<Integer, BlameInfo>> startBlameTask(Path filePath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                File file = filePath.toFile();
                if (!file.exists()) {
                    logger.warn("Blame requested for non-existent file: {}", filePath);
                    return Map.<Integer, BlameInfo>of();
                }
                // Build process: git -C <repoRoot> blame --line-porcelain -- <fileRelativePath>
                // We will attempt to run git blame in the file's parent directory; if this fails, return empty.
                ProcessBuilder pb = new ProcessBuilder("git", "blame", "--line-porcelain", "--", file.getName());
                pb.directory(file.getParentFile());
                pb.redirectErrorStream(true);
                logger.debug("Running git blame in directory: {} for file: {}", file.getParentFile(), file.getName());
                Process p = pb.start();

                try (BufferedReader r =
                        new BufferedReader(new InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    Pattern commitPattern = Pattern.compile("^([0-9a-f]{40})\\s");
                    String line;
                    int currentLine = 0;
                    String currentAuthor = null;
                    String currentSha = null;
                    Long currentAuthorTime = null;
                    ConcurrentHashMap<Integer, BlameInfo> result = new ConcurrentHashMap<>();

                    while ((line = r.readLine()) != null) {
                        if (line.isEmpty()) continue;
                        Matcher m = commitPattern.matcher(line);
                        if (m.find()) {
                            // start of a new block; next lines contain author etc.
                            currentSha = m.group(1);
                            currentAuthor = null;
                            currentAuthorTime = null;
                        } else if (line.startsWith("author ")) {
                            currentAuthor = line.substring("author ".length()).trim();
                        } else if (line.startsWith("author-time ")) {
                            try {
                                currentAuthorTime = Long.parseLong(line.substring("author-time ".length()).trim());
                            } catch (NumberFormatException e) {
                                currentAuthorTime = null;
                            }
                        } else if (line.startsWith("\t")) {
                            // content line; increment line counter and record blame
                            currentLine++;
                            String shortSha = (currentSha != null && currentSha.length() >= 8) ? currentSha.substring(0, 8) : (currentSha == null ? "" : currentSha);
                            // Use 0L as sentinel value for missing timestamp
                            result.put(currentLine, new BlameInfo(currentAuthor == null ? "" : currentAuthor, shortSha, currentAuthorTime != null ? currentAuthorTime : 0L));
                        }
                    }
                    // Wait for process to exit to avoid zombies
                    int exitCode = -1;
                    try {
                        exitCode = p.waitFor();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }

                    if (exitCode != 0) {
                        logger.warn("git blame exited with code {} for file: {}", exitCode, filePath);
                    } else {
                        logger.debug("Blame successful: {} lines for {}", result.size(), filePath);
                    }
                    return Map.copyOf(result);
                }
            } catch (Exception e) {
                // On any failure, return empty map (do not propagate)
                logger.error("Blame failed for {}: {}", filePath, e.getMessage(), e);
                return Map.<Integer, BlameInfo>of();
            }
        });
    }

    /** Clear the cache for a path (useful when files change on disk). */
    public void clearCacheFor(Path filePath) {
        cache.remove(filePath);
    }

    /** Clear all cached blame data (careful). */
    public void clearAllCache() {
        cache.clear();
    }
}
