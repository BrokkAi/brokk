package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class StyleGuideResolverTest {

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

        var resolver = new StyleGuideResolver(master, List.of(fileA, fileB));

        assertTrue(resolver.getOrderedAgentFiles().isEmpty(), "Expected no AGENTS.md files to be found");
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

        var resolver = new StyleGuideResolver(master, List.of(fileX));

        var ordered = resolver.getOrderedAgentFiles();
        assertEquals(1, ordered.size(), "Only root AGENTS.md should be present");
        assertEquals(rootAgents.normalize(), ordered.getFirst());

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

        var resolver = new StyleGuideResolver(master, List.of(fileInNested));

        var ordered = resolver.getOrderedAgentFiles();
        assertEquals(1, ordered.size(), "Only nested AGENTS.md should be present");
        assertEquals(nestedAgents.normalize(), ordered.getFirst());

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
        var resolver = new StyleGuideResolver(master, List.of(fileA1, fileA2, fileB1));

        var ordered = resolver.getOrderedAgentFiles();
        // Expected nearest-first by input groups, preserving first-seen order: A, then B, then root
        List<Path> expected = List.of(agentsA.normalize(), agentsB.normalize(), rootAgents.normalize());
        assertEquals(expected, ordered, "Expected nearest-first order with dedup across inputs");

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
        assertTrue(iA < iB && iB < iR, "Sections should be ordered A, then B, then root");

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
