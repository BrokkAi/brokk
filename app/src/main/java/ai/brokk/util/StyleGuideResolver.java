package ai.brokk.util;

import ai.brokk.analyzer.ProjectFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * Resolves a composite style guide by aggregating AGENTS.md files from a set of
 * input ProjectFile instances.
 */
public final class StyleGuideResolver {
    private static final Logger logger = LogManager.getLogger(StyleGuideResolver.class);

    // Safety caps to prevent huge prompts; nearest-first files are preferred.
    // TODO: Make these caps token-aware rather than character-count based.
    private static final int DEFAULT_MAX_SECTIONS = 8;
    private static final int DEFAULT_MAX_TOTAL_CHARS = 20_000;

    private final Collection<ProjectFile> inputs;

    /**
     * Constructs a StyleGuideResolver that accepts ProjectFile inputs.
     *
     * @param files a list of ProjectFile instances to influence which AGENTS.md files are selected
     */
    public StyleGuideResolver(Collection<ProjectFile> files) {
        this.inputs = files;
    }

    /**
     * @return the set of directories containing all the inputs, and their parents up to the project root;
     * ordered shortest-path-first.
     */
    @VisibleForTesting
    List<ProjectFile> getPotentialDirectories() {
        if (inputs.isEmpty()) {
            return List.of();
        }

        // set of all paths
        var expanded = inputs.stream()
                .flatMap(pf -> {
                    var ancestors = new ArrayList<ProjectFile>();
                    for (var parent = pf.getParent(); parent != null; parent = parent.getParent()) {
                        ancestors.add(new ProjectFile(pf.getRoot(), parent));
                    }
                    // include project root
                    ancestors.add(new ProjectFile(pf.getRoot(), Path.of("")));
                    return ancestors.stream();
                })
                .collect(Collectors.toSet());

        // sort and return: nearest-first (minimal ancestor distance to any input), then lexicographically
        return expanded.stream()
                .sorted((d1, d2) -> {
                    int dist1 = d1.getRelPath().getNameCount();
                    int dist2 = d2.getRelPath().getNameCount();
                    int cmp = Integer.compare(dist1, dist2);
                    if (cmp != 0) return cmp;
                    return d1.getRelPath().toString().compareTo(d2.getRelPath().toString());
                })
                .map(dir -> new ProjectFile(dir.getRoot(), dir.getRelPath().resolve("AGENTS.md")))
                .filter(ProjectFile::exists)
                .collect(Collectors.toList());
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
        var files = getPotentialDirectories();
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
            Path dirRel = agents.getRelPath().getParent();
            String label = (dirRel == null || dirRel.getNameCount() == 0) ? "." : dirRel.toString();
            String header = "### AGENTS.md at " + label;
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
