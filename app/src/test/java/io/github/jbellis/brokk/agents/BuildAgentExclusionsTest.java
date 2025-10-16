package io.github.jbellis.brokk.agents;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class BuildAgentExclusionsTest {

    @Test
    void sanitize_normalizes_paths_and_handles_edge_cases() {
        // Glob patterns preserved (no InvalidPathException)
        assertEquals("**/.idea", BuildAgent.sanitizeExclusion("**/.idea"));
        assertEquals("**/target", BuildAgent.sanitizeExclusion("**\\target"));

        // Leading slashes and backslashes stripped
        assertEquals("dist", BuildAgent.sanitizeExclusion("/dist"));
        assertEquals("nbproject/private", BuildAgent.sanitizeExclusion("\\nbproject\\private"));

        // Whitespace trimmed
        assertEquals("target", BuildAgent.sanitizeExclusion("  target  "));

        // Leading ./ stripped and separators normalized
        assertEquals("foo/bar", BuildAgent.sanitizeExclusion("./foo\\bar"));

        // Empty/whitespace-only returns null
        assertNull(BuildAgent.sanitizeExclusion("   "));
    }

    @Test
    void combine_and_sanitize_merges_and_normalizes() {
        var baseline = List.of("target", "docs");
        var extras = List.of(".idea", " /dist ");
        Set<String> combined = BuildAgent.combineAndSanitizeExcludes(baseline, extras);

        // Order is maintained by LinkedHashSet; presence is what matters
        assertTrue(combined.contains("target"));
        assertTrue(combined.contains("docs"));
        assertTrue(combined.contains(".idea"));
        assertTrue(combined.contains("dist"));
    }
}
