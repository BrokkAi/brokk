package ai.brokk.util;

import ai.brokk.analyzer.ProjectFile;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Resolves a composite style guide by aggregating AGENTS.md files from a set of
 * input ProjectFile instances, walking up the directory tree towards the repository root.
 *
 * Ordering rules:
 * - For each input file, scan nearest-first (current directory up to repository root).
 * - Across multiple inputs, preserve the first-seen order and de-duplicate files.
 *
 * Typical usage:
 *   var resolver = new StyleGuideResolver(projectFiles);
 *   String guide = resolver.resolveCompositeGuide();
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

    private List<ProjectFile> collectAgentsForSingleInput(ProjectFile input) {
        var ordered = new ArrayList<ProjectFile>();

        // Determine starting directory relative to repo root
        ProjectFile cursorDir;
        cursorDir = Files.isDirectory(input.absPath()) ? input : new ProjectFile(input.getRoot(), input.getParent());

        // Walk upwards from start to the repository root (empty relative path)
        while (true) {
            // Candidate "<cursorDir>/AGENTS.md"
            var candidate = new ProjectFile(cursorDir.getRoot(), cursorDir.getRelPath().resolve("AGENTS.md"));
            if (Files.isRegularFile(candidate.absPath())) {
                ordered.add(candidate);
            }

            // Stop after processing the repository root
            if (cursorDir.absPath().equals(cursorDir.getRoot())) {
                break;
            }

            // Move up one level
            cursorDir = new ProjectFile(cursorDir.getRoot(), cursorDir.getParent());
        }

        return ordered;
    }

    /**
     * Finds all AGENTS.md files in nearest-first order for each input path, de-duplicated
     * across all inputs while preserving the first occurrence order.
     *
     * Ordering across multiple inputs is done in "layers" (round-robin by depth):
     * - First include the nearest AGENTS.md for each input (depth 0).
     * - Then include the next level up for each input (depth 1), and so on,
     *   stopping at the repository root. Duplicates are removed while preserving first-seen order.
     */
    public List<ProjectFile> getOrderedAgentFiles() {
        if (inputs.isEmpty()) {
            return List.of();
        }

        // Collect per-input lists (nearest-first) and compute the max depth.
        var perInput = new ArrayList<List<ProjectFile>>(inputs.size());
        int maxDepth = 0;
        for (var pf : inputs) {
            var lst = collectAgentsForSingleInput(pf);
            perInput.add(lst);
            if (lst.size() > maxDepth) {
                maxDepth = lst.size();
            }
        }

        // Interleave by depth to ensure nearest-first across inputs, then go upwards.
        Set<ProjectFile> dedup = new LinkedHashSet<>();
        for (int depth = 0; depth < maxDepth; depth++) {
            for (List<ProjectFile> lst : perInput) {
                if (depth < lst.size()) {
                    dedup.add(lst.get(depth));
                }
            }
        }
        return List.copyOf(dedup);
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
