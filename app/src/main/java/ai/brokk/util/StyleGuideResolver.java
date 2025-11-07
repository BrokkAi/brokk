package ai.brokk.util;

import ai.brokk.analyzer.ProjectFile;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Resolves a composite style guide by aggregating AGENTS.md files from a set of
 * input ProjectFile instances.
 *
 * Simplified approach:
 * - Compute a single, ordered set of unique directories by performing a breadth-first ascent
 *   from all input directories toward the repository root (nearest-first layers across inputs).
 * - Scan each unique directory at most once for AGENTS.md.
 * - Concatenate discovered files in that order.
 */
public final class StyleGuideResolver {
    private static final Logger logger = LogManager.getLogger(StyleGuideResolver.class);

    // Safety caps to prevent huge prompts; nearest-first files are preferred.
    // TODO: Make these caps token-aware rather than character-count based.
    private static final int DEFAULT_MAX_SECTIONS = 8;
    private static final int DEFAULT_MAX_TOTAL_CHARS = 20_000;

    private final List<ProjectFile> inputs;

    /**
     * Constructs a StyleGuideResolver that accepts ProjectFile inputs.
     *
     * @param files a list of ProjectFile instances to influence which AGENTS.md files are selected
     */
    public StyleGuideResolver(List<ProjectFile> files) {
        this.inputs = files;
    }

    /**
     * Finds all AGENTS.md files by:
     * - Building a single ordered set of unique directories starting from all input directories,
     *   walking upwards layer-by-layer toward the repo root (nearest-first across inputs).
     * - Scanning each unique directory once for AGENTS.md and collecting hits in order.
     */
    public List<ProjectFile> getOrderedAgentFiles() {
        if (inputs.isEmpty()) {
            return List.of();
        }

        // Establish the starting directories (one per input; file inputs use their parent directory).
        var startDirs = new ArrayList<ProjectFile>(inputs.size());
        for (var pf : inputs) {
            var dir = pf.isDirectory() ? pf : new ProjectFile(pf.getRoot(), pf.getParent());
            startDirs.add(dir);
        }

        // BFS upwards across all inputs simultaneously, deduping directories while preserving first-seen order.
        var visitedDirs = new LinkedHashSet<ProjectFile>();
        var orderedDirs = new ArrayList<ProjectFile>();
        var frontier = new LinkedHashSet<ProjectFile>(startDirs);

        while (!frontier.isEmpty()) {
            var nextFrontier = new LinkedHashSet<ProjectFile>();
            for (var dir : frontier) {
                if (visitedDirs.add(dir)) {
                    orderedDirs.add(dir);
                    // Move upward unless we are at the repository root.
                    if (!dir.isRepoRoot()) {
                        var parent = new ProjectFile(dir.getRoot(), dir.getParent());
                        nextFrontier.add(parent);
                    }
                }
            }
            frontier = nextFrontier;
        }

        // Scan each unique directory once for AGENTS.md in the established order.
        var result = new ArrayList<ProjectFile>();
        for (var dir : orderedDirs) {
            var candidate = new ProjectFile(dir.getRoot(), dir.getRelPath().resolve("AGENTS.md"));
            if (Files.isRegularFile(candidate.absPath())) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static int getCap(String propName, int defVal) {
        String v = System.getProperty(propName);
        if (v == null || v.isBlank()) return defVal;
        int parsed = Integer.parseInt(v.trim());
        return parsed > 0 ? parsed : defVal;
    }

    /**
     * Builds a composite style guide by concatenating each discovered AGENTS.md file's contents,
     * prefixed with a section header:
     *
     *   ### AGENTS.md at &lt;relative/path&gt;
     *
     * Sections are separated by a blank line. If no AGENTS.md files are found, returns an empty string.
     *
     * Safety:
     * - Caps total sections (nearest-first).
     * - Caps total characters and truncates the last included section if needed.
     * - Appends a note when truncation occurs.
     *
     * TODO: Make limits token-aware using model-specific tokenizers.
     */
    public String resolveCompositeGuide() {
        var files = getOrderedAgentFiles();
        if (files.isEmpty()) {
            logger.debug("No AGENTS.md files found");
            return "";
        }

        // Allow overrides via system properties for experimentation.
        // brokk.style.guide.maxSections and brokk.style.guide.maxChars
        int maxSections = getCap("brokk.style.guide.maxSections", DEFAULT_MAX_SECTIONS);
        int maxChars = getCap("brokk.style.guide.maxChars", DEFAULT_MAX_TOTAL_CHARS);

        var sections = new ArrayList<String>();
        int included = 0;
        int totalCount = files.size();
        int currentChars = 0;
        boolean truncated = false;

        for (var agents : files) {
            if (included >= maxSections) {
                truncated = true;
                logger.debug("Stopping aggregation due to section cap: {} sections.", maxSections);
                break;
            }
            String header = "### AGENTS.md at " + agents.getParent();
            String content = agents.read().orElse("").strip();

            // Compose the section payload with a blank line between header and content
            String section = header + "\n\n" + content;

            // Account for the inter-section separator that will be added during join ("\n\n")
            int separatorLen = sections.isEmpty() ? 0 : 2;
            int projected = currentChars + separatorLen + section.length();

            if (projected <= maxChars) {
                // Add as-is
                if (separatorLen > 0) {
                    currentChars += separatorLen;
                }
                sections.add(section);
                currentChars += section.length();
                included++;
            } else {
                // Try to include a truncated version of this section if there is any space left
                int remaining = maxChars - currentChars - separatorLen;
                if (remaining > 0) {
                    String headerWithSep = header + "\n\n";
                    int headerLen = headerWithSep.length();

                    if (headerLen < remaining) {
                        int remainingForContent = remaining - headerLen;
                        // Reserve a small suffix for a truncation marker
                        String marker = "\n\n[Note: style guide truncated here to fit prompt budget]";
                        int markerLen = marker.length();
                        int finalContentLen = Math.max(0, remainingForContent - markerLen);
                        String truncatedContent = content.substring(0, Math.min(content.length(), finalContentLen));
                        String truncatedSection = headerWithSep + truncatedContent + marker;

                        if (separatorLen > 0) {
                            currentChars += separatorLen;
                        }
                        sections.add(truncatedSection);
                        currentChars += truncatedSection.length();
                        included++;
                    } else {
                        logger.debug("Insufficient space even for header, skipping partial add for {}", agents);
                    }
                }
                truncated = true;
                logger.debug(
                        "Stopping aggregation due to character cap at ~{} chars (cap {}).", currentChars, maxChars);
                break;
            }
        }

        String result = sections.stream().filter(s -> !s.isBlank()).collect(Collectors.joining("\n\n"));

        if (truncated) {
            String note = "\n\n[Note: Truncated aggregated style guide to "
                    + included
                    + " section(s) and "
                    + currentChars
                    + " characters to fit prompt budget. TODO: make this token-aware.]";
            result = result + note;
        }

        logger.debug(
                "Resolved composite style guide: included {} of {} files; chars {}; truncated={}",
                included,
                totalCount,
                currentChars,
                truncated);

        return result;
    }

    /**
     * Convenience function to build the composite guide directly from ProjectFile inputs.
     *
     * @param files a list of ProjectFile inputs used to locate relevant AGENTS.md files
     * @return aggregated style guide content
     */
    public static String resolve(List<ProjectFile> files) {
        return new StyleGuideResolver(files).resolveCompositeGuide();
    }
}
