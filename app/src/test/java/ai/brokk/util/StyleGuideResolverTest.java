package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class StyleGuideResolverTest {

    /**
     * Lightweight smoke test validating that the aggregated style guide contains multiple AGENTS.md
     * sections in nearest-first order, matching what systemMessage() would embed via StyleGuideResolver.
     *
     * This test intentionally avoids wiring a full Context/IContextManager graph.
     */
    @Test
    void systemMessageStyleGuide_nearestFirst_viaResolver(@TempDir Path temp) throws IOException {
        Path master = temp.resolve("repo");
        Files.createDirectories(master);

        // Root AGENTS.md
        Path rootAgents = master.resolve("AGENTS.md");
        Files.writeString(rootAgents, "ROOT");

        // Subproject A with its own AGENTS.md and a file that would appear in the workspace
        Path dirA = master.resolve("a");
        Files.createDirectories(dirA);
        Path agentsA = dirA.resolve("AGENTS.md");
        Files.writeString(agentsA, "A-GUIDE");
        Path fileA = dirA.resolve("src/Foo.java");
        Files.createDirectories(fileA.getParent());
        Files.writeString(fileA, "// foo");

        // Subproject B with its own AGENTS.md and a file that would appear in the workspace
        Path dirB = master.resolve("b");
        Files.createDirectories(dirB);
        Path agentsB = dirB.resolve("AGENTS.md");
        Files.writeString(agentsB, "B-GUIDE");
        Path fileB = dirB.resolve("src/Bar.java");
        Files.createDirectories(fileB.getParent());
        Files.writeString(fileB, "// bar");

        // Aggregate the style guide exactly as prompts do (via StyleGuideResolver)
        var projectFileA = new ProjectFile(master, master.relativize(fileA));
        var projectFileB = new ProjectFile(master, master.relativize(fileB));
        String guide = StyleGuideResolver.resolve(List.of(projectFileA, projectFileB));

        String headerA = "### AGENTS.md at a";
        String headerB = "### AGENTS.md at b";
        String headerRoot = "### AGENTS.md at .";

        int iA = guide.indexOf(headerA);
        int iB = guide.indexOf(headerB);
        int iR = guide.indexOf(headerRoot);

        assertTrue(iA >= 0, "Missing section header for a");
        assertTrue(iB >= 0, "Missing section header for b");
        assertTrue(iR >= 0, "Missing section header for root");

        // Nearest-first ordering across inputs as implemented: root, then a, then b
        assertTrue(iR < iA && iA < iB, "Sections should be ordered root, then a, then b");

        // Contents from each AGENTS.md should be present
        assertTrue(guide.contains("A-GUIDE"));
        assertTrue(guide.contains("B-GUIDE"));
        assertTrue(guide.contains("ROOT"));
    }

    @Test
    void orderedAgentFiles_noneFound_returnsEmpty(@TempDir Path temp) throws IOException {
        Path master = temp.resolve("repo");
        Files.createDirectories(master);

        // Inputs inside the master, but no AGENTS.md anywhere
        Path fileA = master.resolve("a/FileA.txt");
        Path fileB = master.resolve("b/FileB.txt");
        Files.createDirectories(fileA.getParent());
        Files.createDirectories(fileB.getParent());
        Files.writeString(fileA, "foo");
        Files.writeString(fileB, "bar");

        var projectFileA = new ProjectFile(master, master.relativize(fileA));
        var projectFileB = new ProjectFile(master, master.relativize(fileB));
        var resolver = new StyleGuideResolver(List.of(projectFileA, projectFileB));

        assertTrue(resolver.getPotentialDirectories().isEmpty(), "Expected no AGENTS.md files to be found");
        assertEquals("", resolver.resolveCompositeGuide(), "Expected empty composite guide when none found");
    }

    @Test
    void resolveCompositeGuide_onlyRoot(@TempDir Path temp) throws IOException {
        Path master = temp.resolve("repo");
        Files.createDirectories(master);

        Path rootAgents = master.resolve("AGENTS.md");
        Files.writeString(rootAgents, "ROOT-GUIDE");

        Path fileX = master.resolve("x/Some.java");
        Files.createDirectories(fileX.getParent());
        Files.writeString(fileX, "// x");

        var projectFileX = new ProjectFile(master, master.relativize(fileX));
        var resolver = new StyleGuideResolver(List.of(projectFileX));

        var ordered = resolver.getPotentialDirectories();
        assertEquals(1, ordered.size(), "Only root AGENTS.md should be present");
        assertEquals(new ProjectFile(master, master.relativize(rootAgents)), ordered.getFirst());

        String guide = resolver.resolveCompositeGuide();
        assertTrue(guide.contains("### AGENTS.md at ."), "Expected labeled section for root");
        assertTrue(guide.contains("ROOT-GUIDE"), "Expected root content to be included");
    }

    @Test
    void resolveCompositeGuide_onlyNested(@TempDir Path temp) throws IOException {
        Path master = temp.resolve("repo");
        Path nested = master.resolve("pkg");
        Files.createDirectories(nested);

        Path nestedAgents = nested.resolve("AGENTS.md");
        Files.writeString(nestedAgents, "NESTED-GUIDE");

        Path fileInNested = nested.resolve("src/Code.kt");
        Files.createDirectories(fileInNested.getParent());
        Files.writeString(fileInNested, "// kt");

        var projectFileInNested = new ProjectFile(master, master.relativize(fileInNested));
        var resolver = new StyleGuideResolver(List.of(projectFileInNested));

        var ordered = resolver.getPotentialDirectories();
        assertEquals(1, ordered.size(), "Only nested AGENTS.md should be present");
        assertEquals(new ProjectFile(master, master.relativize(nestedAgents)), ordered.getFirst());

        String guide = resolver.resolveCompositeGuide();
        assertTrue(guide.contains("### AGENTS.md at pkg"), "Expected labeled section for nested dir");
        assertTrue(guide.contains("NESTED-GUIDE"), "Expected nested content to be included");
    }

    @Test
    void orderedAgentFiles_nearestFirstAndDedup_multipleInputs(@TempDir Path temp) throws IOException {
        Path master = temp.resolve("repo");
        Files.createDirectories(master);

        // Root AGENTS.md
        Path rootAgents = master.resolve("AGENTS.md");
        Files.writeString(rootAgents, "ROOT");

        // Subtree A
        Path dirA = master.resolve("a");
        Files.createDirectories(dirA);
        Path agentsA = dirA.resolve("AGENTS.md");
        Files.writeString(agentsA, "A-GUIDE");
        Path fileA1 = dirA.resolve("x/File1.txt");
        Path fileA2 = dirA.resolve("z/File2.txt");
        Files.createDirectories(fileA1.getParent());
        Files.createDirectories(fileA2.getParent());
        Files.writeString(fileA1, "a1");
        Files.writeString(fileA2, "a2");

        // Subtree B
        Path dirB = master.resolve("b");
        Files.createDirectories(dirB);
        Path agentsB = dirB.resolve("AGENTS.md");
        Files.writeString(agentsB, "B-GUIDE");
        Path fileB1 = dirB.resolve("y/File3.txt");
        Files.createDirectories(fileB1.getParent());
        Files.writeString(fileB1, "b1");

        // Inputs: two from A subtree (should dedup A's AGENTS.md) and one from B
        var projectFileA1 = new ProjectFile(master, master.relativize(fileA1));
        var projectFileA2 = new ProjectFile(master, master.relativize(fileA2));
        var projectFileB1 = new ProjectFile(master, master.relativize(fileB1));
        var resolver = new StyleGuideResolver(List.of(projectFileA1, projectFileA2, projectFileB1));

        var ordered = resolver.getPotentialDirectories();
        // Expected nearest-first as implemented: root, then A, then B
        List<ProjectFile> expected = List.of(
                new ProjectFile(master, master.relativize(rootAgents)),
                new ProjectFile(master, master.relativize(agentsA)),
                new ProjectFile(master, master.relativize(agentsB)));
        assertEquals(expected, ordered, "Expected nearest-first order with dedup (root first)");

        String guide = resolver.resolveCompositeGuide();
        String headerA = "### AGENTS.md at a";
        String headerB = "### AGENTS.md at b";
        String headerRoot = "### AGENTS.md at .";

        int iA = guide.indexOf(headerA);
        int iB = guide.indexOf(headerB);
        int iR = guide.indexOf(headerRoot);

        assertTrue(iA >= 0, "Missing section header for a");
        assertTrue(iB >= 0, "Missing section header for b");
        assertTrue(iR >= 0, "Missing section header for root");
        assertTrue(iR < iA && iA < iB, "Sections should be ordered root, then A, then B");

        // Ensure content included and deduped (only one A header even with two A inputs)
        assertEquals(1, countOccurrences(guide, headerA), "A section should appear once");
        assertTrue(guide.contains("A-GUIDE"), "Content from a/AGENTS.md should be present");
        assertTrue(guide.contains("B-GUIDE"), "Content from b/AGENTS.md should be present");
        assertTrue(guide.contains("ROOT"), "Content from root AGENTS.md should be present");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while (idx >= 0) {
            idx = text.indexOf(needle, idx);
            if (idx >= 0) {
                count++;
                idx += needle.length();
            }
        }
        return count;
    }
}
