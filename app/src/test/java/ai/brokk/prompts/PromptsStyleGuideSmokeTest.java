package ai.brokk.prompts;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.util.StyleGuideResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lightweight smoke test validating that the aggregated style guide contains multiple AGENTS.md
 * sections in nearest-first order, matching what systemMessage() would embed via StyleGuideResolver.
 *
 * This test intentionally avoids wiring a full Context/IContextManager graph.
 */
public class PromptsStyleGuideSmokeTest {

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
        String guide = StyleGuideResolver.resolve(master, List.of(fileA, fileB));

        String headerA = "### AGENTS.md at a";
        String headerB = "### AGENTS.md at b";
        String headerRoot = "### AGENTS.md at .";

        int iA = guide.indexOf(headerA);
        int iB = guide.indexOf(headerB);
        int iR = guide.indexOf(headerRoot);

        assertTrue(iA >= 0, "Missing section header for a");
        assertTrue(iB >= 0, "Missing section header for b");
        assertTrue(iR >= 0, "Missing section header for root");

        // Nearest-first ordering across inputs (A, then B, then root)
        assertTrue(iA < iB && iB < iR, "Sections should be ordered a, then b, then root");

        // Contents from each AGENTS.md should be present
        assertTrue(guide.contains("A-GUIDE"));
        assertTrue(guide.contains("B-GUIDE"));
        assertTrue(guide.contains("ROOT"));
    }
}
