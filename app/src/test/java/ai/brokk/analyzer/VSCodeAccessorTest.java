package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test to verify that TypeScript accessor properties (getters/setters) in VSCode-style code
 * don't generate duplicate FQName warnings.
 */
public class VSCodeAccessorTest {

    private static final Logger logger = LoggerFactory.getLogger(VSCodeAccessorTest.class);

    @Test
    void testVSCodeWebviewViewPaneAccessors(@TempDir Path tempDir) throws IOException {
        // Recreate the VSCode pattern that was triggering the warning
        var testFile = tempDir.resolve("webviewViewPane.ts");
        Files.writeString(
                testFile,
                """
            export class WebviewViewPane {
                private badge: IViewBadge | undefined;

                get badge(): IViewBadge | undefined { return self.badge; }
                set badge(badge: IViewBadge | undefined) { self.updateBadge(badge); }
            }
            """);

        var project = new TestProject(tempDir, Languages.TYPESCRIPT);
        var analyzer = new TypescriptAnalyzer(project);

        // Get badge accessor CodeUnits
        var projectFile = new ProjectFile(tempDir, tempDir.relativize(testFile));
        var badgeAccessors = analyzer.getDeclarations(projectFile).stream()
                .filter(cu -> cu.fqName().contains("badge"))
                .filter(cu -> cu.fqName().endsWith("$get") || cu.fqName().endsWith("$set"))
                .toList();

        logger.info(
                "Badge accessors found: {}",
                badgeAccessors.stream().map(CodeUnit::fqName).toList());

        // Verify both getter and setter are present with distinct FQNames
        assertEquals(2, badgeAccessors.size(), "Should have 2 badge accessors");

        var fqNames = badgeAccessors.stream().map(CodeUnit::fqName).sorted().toList();

        assertTrue(fqNames.getFirst().endsWith("$get"), "First should be getter: " + fqNames.getFirst());
        assertTrue(fqNames.getLast().endsWith("$set"), "Second should be setter: " + fqNames.getLast());

        // Both should have the same base name
        var getterBase = fqNames.getFirst().replace("$get", "");
        var setterBase = fqNames.getLast().replace("$set", "");
        assertEquals(getterBase, setterBase, "Getter and setter should have the same base FQName");
    }
}
