package ai.brokk.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Resolves a composite style guide by aggregating AGENTS.md files from a set of
 * input paths, walking up the directory tree towards a repository/project master root.
 *
 * Ordering rules:
 * - For each input path, scan nearest-first (current directory up to master root).
 * - Across multiple inputs, preserve the first-seen order and de-duplicate files.
 *
 * Typical usage:
 *   var resolver = new StyleGuideResolver(masterRoot, filePaths);
 *   String guide = resolver.resolveCompositeGuide();
 */
public final class StyleGuideResolver {
    private static final Logger logger = LogManager.getLogger(StyleGuideResolver.class);

    private final Path masterRoot;
    private final List<Path> normalizedInputs;

    /**
     * @param masterRoot the top-level project root (e.g. AbstractProject.getMasterRootPathForConfig())
     * @param filePaths absolute or project-relative paths to files (or directories) that should influence
     *                  which AGENTS.md files are selected
     */
    public StyleGuideResolver(Path masterRoot, Collection<Path> filePaths) {
        Objects.requireNonNull(masterRoot, "masterRoot");
        Objects.requireNonNull(filePaths, "filePaths");
        this.masterRoot = masterRoot.toAbsolutePath().normalize();
        this.normalizedInputs = normalizeInputs(this.masterRoot, filePaths);
    }

    private static List<Path> normalizeInputs(Path masterRoot, Collection<Path> inputs) {
        var result = new ArrayList<Path>();
        for (var p : inputs) {
            if (p == null) continue;
            Path abs = p.isAbsolute() ? p.normalize() : masterRoot.resolve(p).normalize();
            if (!abs.startsWith(masterRoot)) {
                logger.debug("Skipping path outside master root: {} (masterRoot: {})", abs, masterRoot);
                continue;
            }
            result.add(abs);
        }
        return result;
    }

    private List<Path> collectAgentsForSingleInput(Path absPath) {
        var ordered = new ArrayList<Path>();

        Path startDir;
        if (Files.isDirectory(absPath)) {
            startDir = absPath;
        } else {
            Path parent = absPath.getParent();
            startDir = (parent != null) ? parent : masterRoot;
        }

        Path cursor = startDir;
        while (cursor != null) {
            if (!cursor.startsWith(masterRoot)) {
                // Never walk above masterRoot
                break;
            }

            Path candidate = cursor.resolve("AGENTS.md");
            if (Files.isRegularFile(candidate)) {
                ordered.add(candidate.normalize());
            }

            if (cursor.equals(masterRoot)) {
                break;
            }
            cursor = cursor.getParent();
        }

        return ordered;
    }

    /**
     * Finds all AGENTS.md files in nearest-first order for each input path, de-duplicated
     * across all inputs while preserving the first occurrence order.
     */
    public List<Path> getOrderedAgentFiles() {
        Set<Path> dedup = new LinkedHashSet<>();
        for (var p : normalizedInputs) {
            for (var found : collectAgentsForSingleInput(p)) {
                dedup.add(found);
            }
        }
        return List.copyOf(dedup);
    }

    private static String toUnix(Path p) {
        return p.toString().replace('\\', '/');
    }

    private String relativeDirLabel(Path agentsFile) {
        Path dir = agentsFile.getParent();
        if (dir == null) {
            return "."; // Shouldn't happen, but be safe
        }
        Path rel = masterRoot.relativize(dir);
        String s = toUnix(rel);
        return s.isEmpty() ? "." : s;
    }

    /**
     * Builds a composite style guide by concatenating each discovered AGENTS.md file's contents,
     * prefixed with a section header:
     *
     *   ### AGENTS.md at &lt;relative/path&gt;
     *
     * Sections are separated by a blank line. If no AGENTS.md files are found, returns an empty string.
     */
    public String resolveCompositeGuide() {
        var files = getOrderedAgentFiles();
        if (files.isEmpty()) {
            logger.debug("No AGENTS.md files found under {}", masterRoot);
            return "";
        }

        var sections = new ArrayList<String>();
        for (var agents : files) {
            try {
                String header = "### AGENTS.md at " + relativeDirLabel(agents);
                String content = Files.readString(agents);
                sections.add(header + "\n\n" + content.strip());
            } catch (IOException e) {
                logger.warn("Failed reading AGENTS.md at {}: {}", agents, e.getMessage());
            }
        }

        return sections.stream().filter(s -> !s.isBlank()).collect(Collectors.joining("\n\n"));
    }

    /**
     * Convenience function to build the composite guide directly.
     */
    public static String resolve(Path masterRoot, Collection<Path> filePaths) {
        return new StyleGuideResolver(masterRoot, filePaths).resolveCompositeGuide();
    }
}
